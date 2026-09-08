package com.github.codersanket24.mycpassistant.runner

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Compiles a single solution file with `javac` and runs explicit test cases against it.
 *
 * **Stdin fix:** Uses [ProcessBuilder] with a dedicated daemon thread that writes
 * stdin and closes the stream — preventing the deadlock that occurs when the process
 * waits for input while we wait for its output on the same thread.
 *
 * **API:** Test cases are passed as `(input, expected)` pairs from the UI so
 * the user's edits in the panel are always used (not stale file contents).
 */
object CPTestRunner {

    private val log = thisLogger()
    const val TEST_TIMEOUT_MS    = 5_000
    const val COMPILE_TIMEOUT_MS = 30_000

    /**
     * Entry-point — safe to call from the EDT.
     *
     * @param sourceFile Solution `.java` / `.kt` file.
     * @param testCases  List of (input, expected) pairs read from the UI cards.
     * @param onStart    Called on EDT before work begins (disable run button / show spinner).
     * @param onComplete Called on EDT with the finished [List] of [TestResult]s.
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

        // Flush all open editor buffers to disk so javac reads the latest code.
        // IntelliJ keeps edits in memory until auto-save; without this, the old
        // .java file on disk is compiled instead of what the user just typed.
        FileDocumentManager.getInstance().saveAllDocuments()

        ApplicationManager.getApplication().executeOnPooledThread {
            val results = executeAll(sourceFile, testCases)
            ApplicationManager.getApplication().invokeLater { onComplete(results) }
        }
    }

    // ── Core logic ─────────────────────────────────────────────────────────

    private fun executeAll(
        sourceFile: VirtualFile,
        testCases: List<Pair<String, String>>,
    ): List<TestResult> {
        val classOutDir = createTempClassDir(sourceFile.nameWithoutExtension)

        val compileError = compileSingleFile(sourceFile, classOutDir)
        if (compileError != null) {
            classOutDir.deleteRecursively()
            return listOf(buildError(1, "Compilation failed:\n$compileError"))
        }

        val mainClass = detectMainClass(sourceFile)
        val javaExe   = resolveJavaExe()

        val results = testCases.mapIndexed { idx, (input, expected) ->
            runSingleTest(javaExe, classOutDir.absolutePath, mainClass, idx + 1, input, expected)
        }

        classOutDir.deleteRecursively()
        return results
    }

    // ── Compilation ────────────────────────────────────────────────────────

    private fun compileSingleFile(sourceFile: VirtualFile, classOutDir: File): String? {
        val javacExe = resolveJavacExe()
            ?: return "javac not found. Ensure a JDK (not just JRE) is installed."
        classOutDir.mkdirs()
        return try {
            val cmd = GeneralCommandLine(javacExe, "-d", classOutDir.absolutePath, sourceFile.path)
                .withCharset(StandardCharsets.UTF_8)
            val out = CapturingProcessHandler(cmd).runProcess(COMPILE_TIMEOUT_MS)
            if (out.exitCode == 0) null
            else out.stderr.ifBlank { "javac exited with code ${out.exitCode}" }
        } catch (ex: Exception) {
            "javac error: ${ex.message}"
        }
    }

    // ── Execution ──────────────────────────────────────────────────────────

    /**
     * Runs one test case.
     *
     * **Stdin strategy:** a daemon thread writes the entire input string and
     * closes the process's stdin (signalling EOF). Meanwhile the main thread
     * reads stdout and stderr via [CompletableFuture]s. The process is killed
     * after [TEST_TIMEOUT_MS] ms if it hasn't exited yet.
     */
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
            val process = ProcessBuilder(javaExe, "-cp", classPath, mainClass)
                .start()

            // Write stdin in a separate daemon thread to avoid deadlock.
            // We must close the stream (use{}) to send EOF to the program.
            Thread {
                try {
                    process.outputStream.use {
                        it.write(input.toByteArray(StandardCharsets.UTF_8))
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            // Capture stdout and stderr concurrently so neither buffer fills up.
            val stdoutFuture = CompletableFuture.supplyAsync {
                process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
            }

            // Wait for the process (with timeout)
            val finished = process.waitFor(TEST_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            val elapsed  = System.currentTimeMillis() - start

            if (!finished) {
                process.destroyForcibly()          // closes all streams → futures finish
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
            log.warn("CPTestRunner: error running test $index", ex)
            TestResult(index, input, expected, "", TestResult.Status.ERROR,
                System.currentTimeMillis() - start, ex.message ?: "Unknown error")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Right-trims each line, drops trailing blank lines, then compares. */
    private fun outputMatches(expected: String, got: String): Boolean {
        fun normalise(s: String) = s.trim().lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
        return normalise(expected) == normalise(got)
    }

    private fun detectMainClass(f: VirtualFile): String {
        val cls = f.nameWithoutExtension
        return try {
            val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
                .find(com.intellij.openapi.vfs.VfsUtil.loadText(f))?.groupValues?.getOrNull(1)
            if (!pkg.isNullOrBlank()) "$pkg.$cls" else cls
        } catch (_: Exception) { cls }
    }

    private fun resolveJavaExe(): String {
        val ext = if (isWindows()) ".exe" else ""
        val c = File(System.getProperty("java.home"), "bin/java$ext")
        return if (c.exists()) c.absolutePath else "java"
    }

    private fun resolveJavacExe(): String? {
        val ext = if (isWindows()) ".exe" else ""
        val c = File(System.getProperty("java.home"), "bin/javac$ext")
        return if (c.exists()) c.absolutePath else null
    }

    private fun createTempClassDir(prefix: String) =
        File(System.getProperty("java.io.tmpdir"), "myCPA_${prefix}_${System.currentTimeMillis()}")

    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")

    private fun buildError(index: Int, msg: String) =
        TestResult(index, "", "", msg, TestResult.Status.ERROR, 0L, msg)
}
