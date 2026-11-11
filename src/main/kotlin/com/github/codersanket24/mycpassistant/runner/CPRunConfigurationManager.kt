package com.github.codersanket24.mycpassistant.runner

import com.github.codersanket24.mycpassistant.settings.AppSettingsState
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
 */
object CPRunConfigurationManager {

    private val log = thisLogger()

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

            val settingsState = AppSettingsState.getInstance()
            val projectDir    = project.basePath
            val testDir       = if (projectDir != null) File(projectDir, settingsState.testCasesDirectory.ifBlank { ".cptests" }) else outDir
            val input1        = File(testDir, "${problemName}_input1.txt").takeIf { it.exists() }
                ?: File(outDir, "${problemName}_input1.txt")

            val existing = runManager.allSettings.firstOrNull { it.name == configName }
            val settings = existing ?: runManager.createConfiguration(
                configName,
                ApplicationConfigurationType::class.java,
            )

            val config = settings.configuration as ApplicationConfiguration

            val module = ModuleManager.getInstance(project).modules.firstOrNull()
            if (module != null) config.setModule(module)

            config.mainClassName    = mainClass
            config.workingDirectory = outDir.absolutePath

            if (input1.exists()) {
                setStdinRedirectViaReflection(config, input1.absolutePath)
            }

            if (existing == null) runManager.addConfiguration(settings)
            runManager.selectedConfiguration = settings

            log.info("CPRunConfigurationManager: set run config '$configName' (main=$mainClass)")
        } catch (ex: Exception) {
            log.warn("CPRunConfigurationManager: could not create run config for '$problemName'", ex)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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
