package com.github.codersanket24.mycpassistant.runner

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Creates (or updates) an IntelliJ [ApplicationConfiguration] for the generated
 * solution file so the user can press ▶️ immediately after a problem is parsed.
 *
 * The configuration:
 *  - Is named `"CP: <ProblemName>"`.
 *  - Sets the main class to the FQCN detected from the source file.
 *  - Redirects stdin from `_input1.txt` (the first test case).
 *  - Becomes the **selected** run configuration automatically.
 *
 * **Must be called on the EDT** (already guaranteed by [ProblemFileGenerator]'s invokeLater).
 */
object CPRunConfigurationManager {

    private val log = thisLogger()

    /**
     * @param project     The open IntelliJ project.
     * @param problemName The sanitised problem/class name (e.g. "Watermelon").
     * @param sourceFile  The generated solution [VirtualFile].
     * @param outDir      The output directory [File] where test files also live.
     */
    fun createOrUpdate(
        project: Project,
        problemName: String,
        sourceFile: VirtualFile,
        outDir: File,
    ) {
        try {
            val runManager   = RunManager.getInstance(project)
            val configName   = "CP: $problemName"
            val mainClass    = detectMainClass(sourceFile)
            val input1       = File(outDir, "${problemName}_input1.txt")

            // Reuse existing config with same name, or create a new one
            val existing = runManager.allSettings.firstOrNull { it.name == configName }
            val settings = existing ?: runManager.createConfiguration(
                configName,
                ApplicationConfigurationType::class.java,
            )

            val config = settings.configuration as ApplicationConfiguration

            // Assign to first available module (needed for classpath resolution)
            val module = ModuleManager.getInstance(project).modules.firstOrNull()
            if (module != null) config.setModule(module)

            config.mainClassName    = mainClass
            config.workingDirectory = outDir.absolutePath

            // setRedirectInput / setInputFilePath are defined on CommonJavaRunConfigurationParameters
            // and exposed differently across IDEA versions — use reflection for safety.
            if (input1.exists()) {
                setStdinRedirectViaReflection(config, input1.absolutePath)
            }

            if (existing == null) runManager.addConfiguration(settings)
            runManager.selectedConfiguration = settings

            log.info("CPRunConfigurationManager: set run config '$configName' (main=$mainClass)")
        } catch (ex: Exception) {
            // Non-fatal — the user can create a run config manually
            log.warn("CPRunConfigurationManager: could not create run config for '$problemName'", ex)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Reads the first `package` declaration in the source file to build the FQCN.
     * Falls back to just the class name (default package) if none is found.
     *
     * e.g. `package solutions` + filename `Watermelon.java` → `solutions.Watermelon`
     */
    private fun detectMainClass(sourceFile: VirtualFile): String {
        val className = sourceFile.nameWithoutExtension
        return try {
            val content = VfsUtil.loadText(sourceFile)
            val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
                .find(content)?.groupValues?.getOrNull(1)
            if (!pkg.isNullOrBlank()) "$pkg.$className" else className
        } catch (_: Exception) {
            className
        }
    }

    /**
     * Calls `setRedirectInput(true)` and `setInputFilePath(path)` via reflection.
     *
     * These methods live on [com.intellij.execution.configurations.CommonJavaRunConfigurationParameters]
     * but their Kotlin property names vary between IDEA versions, so reflection is the safest approach.
     */
    private fun setStdinRedirectViaReflection(config: ApplicationConfiguration, inputPath: String) {
        try {
            config.javaClass
                .getMethod("setRedirectInput", Boolean::class.javaPrimitiveType)
                .invoke(config, true)
            config.javaClass
                .getMethod("setInputFilePath", String::class.java)
                .invoke(config, inputPath)
            log.info("CPRunConfigurationManager: stdin redirect set to $inputPath")
        } catch (ex: Exception) {
            log.info("CPRunConfigurationManager: stdin redirect not available in this IDEA version (${ex.message})")
        }
    }
}
