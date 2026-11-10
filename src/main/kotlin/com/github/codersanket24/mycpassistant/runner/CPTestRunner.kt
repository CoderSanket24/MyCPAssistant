package com.github.codersanket24.mycpassistant.runner

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Compiles and runs CP solution test cases.
 *
 * ## Key design decisions
 *
 * ### Live editor content
 * Reads live in-memory content from [FileDocumentManager] so unsaved changes
 * are compiled and executed without forcing the user to hit Ctrl+S.
 *
 * ### Full result broadcasting
 * Errors (e.g. compilation failure or read errors) are broadcast to ALL test cases
 * so no card in the UI is left stuck in the "Running..." state.
 *
 * ### Stdin via daemon thread
 * Process stdin is fed in a separate daemon thread to avoid deadlock on output buffers.
 */
object CPTestRunner {

    private val log = thisLogger()
    const val TEST_TIMEOUT_MS    = 5_000
    const val COMPILE_TIMEOUT_MS = 30_000

    /**
     * Entry-point — safe to call from the EDT (e.g. button click).
     */
    fun runAll(
        project: Project,
        sourceFile: VirtualFile,
        testCases: List<Pair<String, String>>,
        onStart: () -> Unit = {},
        onComplete: (List<TestResult>) -> Unit,
    ) {
        if (testCases.isEmpty()) {
            onComplete(emptyList())
            return
        }
        onStart()

        ApplicationManager.getApplication().executeOnPooledThread {
            val results = try {
                executeAll(sourceFile, testCases)
            } catch (ex: Exception) {
                log.warn("CPTestRunner: unexpected error", ex)
                testCases.mapIndexed { idx, (inp, exp) ->
                    TestResult(idx + 1, inp, exp, "", TestResult.Status.ERROR, 0L, "Unexpected error: ${ex.message}")
                }
            }
            ApplicationManager.getApplication().invokeLater { onComplete(results) }
        }
    }

    // ── Core pipeline ──────────────────────────────────────────────────────

    private fun executeAll(
        sourceFile: VirtualFile,
        testCases: List<Pair<String, String>>,
    ): List<TestResult> {
        val className = sourceFile.nameWithoutExtension
        val workDir   = createTempWorkDir(className)

        return try {
            // 1. Read live in-memory content
            val sourceContent = readVfsContent(sourceFile)
            if (sourceContent == null) {
                val errorMsg = "Cannot read source file: ${sourceFile.name}"
                return testCases.mapIndexed { idx, (inp, exp) ->
                    TestResult(idx + 1, inp, exp, "", TestResult.Status.ERROR, 0L, errorMsg)
                }
            }

            // 2. Write to temp file for javac
            val srcFile = File(workDir, "$className.java")
            srcFile.parentFile.mkdirs()
            srcFile.writeText(sourceContent, Charsets.UTF_8)

            // 3. Compile
            val classOutDir = File(workDir, "classes").also { it.mkdirs() }
            val compileError = compileSingleFile(srcFile, classOutDir)
            if (compileError != null) {
                val errorMsg = "Compilation failed:\n$compileError"
                return testCases.mapIndexed { idx, (inp, exp) ->
                    TestResult(idx + 1, inp, exp, "", TestResult.Status.ERROR, 0L, errorMsg)
                }
            }

            // 4. Detect FQCN
            val mainClass = detectMainClass(sourceContent, className)
            val javaExe   = resolveJavaExe()

            // 5. Run each test case
            testCases.mapIndexed { idx, (input, expected) ->
                runSingleTest(javaExe, classOutDir.absolutePath, mainClass, idx + 1, input, expected)
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    // ── Compilation ────────────────────────────────────────────────────────

    private fun compileSingleFile(srcFile: File, classOutDir: File): String? {
        val javacExe = resolveJavacExe()
        return try {
            val cmd = GeneralCommandLine(javacExe, "-d", classOutDir.absolutePath, srcFile.absolutePath)
                .withCharset(StandardCharsets.UTF_8)
            val out = CapturingProcessHandler(cmd).runProcess(COMPILE_TIMEOUT_MS)
            if (out.exitCode == 0) null
            else out.stderr.ifBlank { "javac exited with code ${out.exitCode}" }
        } catch (ex: Exception) {
            "javac error: ${ex.message}"
        }
    }

    // ── Execution ──────────────────────────────────────────────────────────

    private fun runSingleTest(
        javaExe: String,
        classPath: String,
        mainClass: String,
        index: Int,
        input: String,
        expected: String,
    ): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(javaExe, "-cp", classPath, mainClass).start()

            // Feed stdin in daemon thread
            Thread {
                try { process.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) } }
                catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            val stdoutFuture = CompletableFuture.supplyAsync {
                process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
            }

            val finished = process.waitFor(TEST_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            val elapsed  = System.currentTimeMillis() - start

            if (!finished) {
                process.destroyForcibly()
                val partial = try { stdoutFuture.get(1, TimeUnit.SECONDS) } catch (_: Exception) { "" }
                return TestResult(index, input, expected, partial, TestResult.Status.TIMEOUT, elapsed)
            }

            val stdout   = stdoutFuture.get(2, TimeUnit.SECONDS)
            val stderr   = stderrFuture.get(2, TimeUnit.SECONDS)
            val exitCode = process.exitValue()

            when {
                exitCode != 0 -> TestResult(
                    index, input, expected, stdout, TestResult.Status.ERROR, elapsed,
                    "Exit code $exitCode\n${stderr.take(400)}"
                )
                // If expected output is empty or matches stdout, consider it PASS
                expected.isBlank() || outputMatches(expected, stdout) -> TestResult(
                    index, input, expected, stdout, TestResult.Status.PASS, elapsed
                )
                else -> TestResult(
                    index, input, expected, stdout, TestResult.Status.FAIL, elapsed
                )
            }
        } catch (ex: Exception) {
            TestResult(
                index, input, expected, "", TestResult.Status.ERROR,
                System.currentTimeMillis() - start, ex.message ?: "Unknown error"
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Reads the live editor content — includes unsaved changes, safe from any thread. */
    private fun readVfsContent(vf: VirtualFile): String? = try {
        ApplicationManager.getApplication().runReadAction<String> {
            FileDocumentManager.getInstance().getDocument(vf)?.text ?: VfsUtil.loadText(vf)
        }
    } catch (ex: Exception) {
        log.warn("readVfsContent failed: ${ex.message}")
        null
    }

    /** Extracts the FQCN by scanning source text for a package declaration. */
    private fun detectMainClass(sourceContent: String, className: String): String {
        val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
            .find(sourceContent)?.groupValues?.getOrNull(1)
        return if (!pkg.isNullOrBlank()) "$pkg.$className" else className
    }

    /** Right-trims each line, drops trailing blank lines, then compares. */
    private fun outputMatches(expected: String, got: String): Boolean {
        fun norm(s: String) = s.trim().lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
        return norm(expected) == norm(got)
    }

    private fun resolveJavaExe(): String {
        val ext = if (isWindows()) ".exe" else ""
        val fromJavaHome = File(System.getProperty("java.home"), "bin/java$ext")
        if (fromJavaHome.exists()) return fromJavaHome.absolutePath

        val fromEnv = System.getenv("JAVA_HOME")?.let { File(it, "bin/java$ext") }
        if (fromEnv != null && fromEnv.exists()) return fromEnv.absolutePath

        return "java"
    }

    private fun resolveJavacExe(): String {
        val ext = if (isWindows()) ".exe" else ""
        val fromJavaHome = File(System.getProperty("java.home"), "bin/javac$ext")
        if (fromJavaHome.exists()) return fromJavaHome.absolutePath

        val fromEnv = System.getenv("JAVA_HOME")?.let { File(it, "bin/javac$ext") }
        if (fromEnv != null && fromEnv.exists()) return fromEnv.absolutePath

        return "javac"
    }

    private fun createTempWorkDir(prefix: String) =
        File(System.getProperty("java.io.tmpdir"), "myCPA_${prefix}_${System.currentTimeMillis()}")

    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")
}
