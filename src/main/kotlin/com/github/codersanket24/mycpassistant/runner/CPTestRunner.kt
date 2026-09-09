package com.github.codersanket24.mycpassistant.runner

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
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
 * ### No saveAllDocuments()
 * IntelliJ 2025+ requires a write-intent lock to call [com.intellij.openapi.fileEditor.FileDocumentManager.saveAllDocuments],
 * but button-click EDT callbacks are write-unsafe. Instead, we read the
 * **live in-memory VirtualFile content** via [ApplicationManager.getApplication().runReadAction]
 * and write it to a temp `.java` file — javac never touches the on-disk file directly.
 * This always compiles what the user has typed, without needing any file save.
 *
 * ### Guaranteed onComplete
 * A try/catch wraps the entire background work so [onComplete] is **always** called —
 * even on unexpected exceptions — preventing the Run button from staying permanently disabled.
 *
 * ### Stdin via daemon thread
 * A daemon thread writes stdin and closes the stream (EOF) while the main
 * thread reads stdout/stderr via [CompletableFuture]s, preventing deadlocks.
 */
object CPTestRunner {

    private val log = thisLogger()
    const val TEST_TIMEOUT_MS    = 5_000
    const val COMPILE_TIMEOUT_MS = 30_000

    /**
     * Entry-point — safe to call from the EDT (e.g. a button-click handler).
     *
     * @param sourceFile  The solution `.java` file open in the editor.
     * @param testCases   (input, expected) pairs read from the UI cards.
     * @param onStart     Called on EDT before work begins (disable button / show spinner).
     * @param onComplete  Called on EDT with results — **always called**, even on error.
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
            // Guaranteed to call onComplete so the Run button is always re-enabled.
            val results = try {
                executeAll(sourceFile, testCases)
            } catch (ex: Exception) {
                log.warn("CPTestRunner: unexpected error", ex)
                listOf(buildError(1, "Unexpected error: ${ex.message}"))
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
            // 1. Read the LIVE in-memory content from the VirtualFile.
            //    runReadAction is safe from background threads and always returns
            //    what the user has typed (even unsaved changes).
            val sourceContent = readVfsContent(sourceFile)
                ?: return listOf(buildError(1, "Cannot read source file: ${sourceFile.name}"))

            // 2. Write content to a temp .java file so javac has a path to compile.
            val srcFile = File(workDir, "$className.java")
            srcFile.parentFile.mkdirs()
            srcFile.writeText(sourceContent, Charsets.UTF_8)

            // 3. Compile with javac into a classes/ sub-directory.
            val classOutDir = File(workDir, "classes").also { it.mkdirs() }
            val compileError = compileSingleFile(srcFile, classOutDir)
            if (compileError != null) {
                return listOf(buildError(1, "Compilation failed:\n$compileError"))
            }

            // 4. Detect the FQCN (reads package declaration from the in-memory source).
            val mainClass = detectMainClass(sourceContent, className)
            val javaExe   = resolveJavaExe()

            // 5. Run each test case.
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
            ?: return "javac not found. Ensure a JDK (not just JRE) is on PATH."
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

            // Write stdin in daemon thread; close stream to signal EOF to the program.
            Thread {
                try { process.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) } }
                catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            // Read stdout + stderr concurrently to avoid buffer-full deadlock.
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
                exitCode != 0 -> TestResult(index, input, expected,
                    stdout, TestResult.Status.ERROR, elapsed,
                    "Exit code $exitCode\n${stderr.take(400)}")
                outputMatches(expected, stdout) -> TestResult(index, input, expected,
                    stdout, TestResult.Status.PASS, elapsed)
                else -> TestResult(index, input, expected, stdout, TestResult.Status.FAIL, elapsed)
            }
        } catch (ex: Exception) {
            TestResult(index, input, expected, "", TestResult.Status.ERROR,
                System.currentTimeMillis() - start, ex.message ?: "Unknown error")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Reads the live editor content — includes unsaved changes, safe from any thread. */
    private fun readVfsContent(vf: VirtualFile): String? = try {
        ApplicationManager.getApplication().runReadAction<String> {
            // Document.text = in-memory editor buffer (includes unsaved edits).
            // VfsUtil.loadText = on-disk content (stale if user hasn't saved).
            // Always prefer the Document so Ctrl+S is never required before running.
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                .getDocument(vf)?.text
                ?: VfsUtil.loadText(vf)   // fallback: file not open in any editor
        }
    } catch (ex: Exception) {
        log.warn("readVfsContent failed: ${ex.message}")
        null
    }

    /** Extracts the FQCN by scanning the source text for a `package` declaration. */
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
        val f = File(System.getProperty("java.home"), "bin/java$ext")
        return if (f.exists()) f.absolutePath else "java"
    }

    private fun resolveJavacExe(): String? {
        val ext = if (isWindows()) ".exe" else ""
        val f = File(System.getProperty("java.home"), "bin/javac$ext")
        return if (f.exists()) f.absolutePath else null
    }

    private fun createTempWorkDir(prefix: String) =
        File(System.getProperty("java.io.tmpdir"), "myCPA_${prefix}_${System.currentTimeMillis()}")

    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")

    private fun buildError(index: Int, msg: String) =
        TestResult(index, "", "", msg, TestResult.Status.ERROR, 0L, msg)
}
