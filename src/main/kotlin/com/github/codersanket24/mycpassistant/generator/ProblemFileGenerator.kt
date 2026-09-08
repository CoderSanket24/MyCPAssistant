package com.github.codersanket24.mycpassistant.generator

import com.github.codersanket24.mycpassistant.model.ProblemData
import com.github.codersanket24.mycpassistant.runner.CPRunConfigurationManager
import com.github.codersanket24.mycpassistant.settings.AppSettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.time.LocalDate

/**
 * Responsible for all file-system operations triggered by a parsed problem:
 *
 *  1. Sanitise the problem name → valid identifier.
 *  2. Resolve (and create) the output directory inside the project.
 *  3. Expand the user's code template with concrete values.
 *  4. Write the solution source file and numbered test-case files.
 *  5. Open the solution file in the editor on the EDT.
 *  6. Show a success notification balloon.
 *
 * **Threading contract**
 *  - [generate] may be called from any thread (e.g. the server's daemon thread).
 *  - All VFS / PSI writes are wrapped in [WriteCommandAction].
 *  - Editor interaction and notifications are dispatched via [ApplicationManager.getApplication().invokeLater].
 */
object ProblemFileGenerator {

    private val log = thisLogger()
    private const val NOTIFICATION_GROUP = "MyCPAssistant"

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Entry-point. Safe to call from a background/daemon thread.
     *
     * @param project IntelliJ project handle.
     * @param problem Parsed payload from Competitive Companion.
     */
    fun generate(project: Project, problem: ProblemData) {
        val settings   = AppSettingsState.getInstance()
        val safeName   = sanitizeName(problem.name)
        val projectDir = project.basePath
            ?: return log.warn("Project base path is null; cannot generate files.")

        val outDir = File(projectDir, settings.outputDirectory)

        // All VFS mutations must happen inside a WriteCommandAction
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                // 1. Ensure output directory exists (VFS-aware)
                val outVDir = VfsUtil.createDirectoryIfMissing(outDir.absolutePath)
                    ?: error("Could not create output directory: ${outDir.absolutePath}")

                // 1b. Register the directory as a source root so IntelliJ compiles it.
                //     Without this, the ▶️ run button gets ClassNotFoundException.
                ensureSourceRoot(project, outVDir)

                // 2. Write solution source file
                val sourceContent = expandTemplate(settings.codeTemplate, safeName, problem)
                val sourceFile    = writeFile(outVDir, "$safeName${settings.fileExtension}", sourceContent)

                // 3. Write test-case files
                problem.tests.forEachIndexed { idx, tc ->
                    val n = idx + 1
                    writeFile(outVDir, "${safeName}_input$n.txt",  tc.input)
                    writeFile(outVDir, "${safeName}_output$n.txt", tc.output)
                }

                // 4. Open in editor + run config + populate CP Tests panel + notify (EDT)
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(sourceFile, /* focusEditor = */ true)
                    CPRunConfigurationManager.createOrUpdate(project, safeName, sourceFile, outDir)

                    // Auto-populate the CP Tests tool window with the new test cases
                    com.github.codersanket24.mycpassistant.toolWindow.CPTestToolWindowFactory
                        .showAndGetPanel(project)
                        ?.loadFromFile(sourceFile, safeName)

                    showNotification(
                        project,
                        "✅ Problem parsed: ${problem.name}",
                        "Created $safeName${settings.fileExtension} with ${problem.tests.size} test case(s).",
                    )
                }
            } catch (ex: Exception) {
                log.error("ProblemFileGenerator failed for '${problem.name}'", ex)
                ApplicationManager.getApplication().invokeLater {
                    showNotification(
                        project,
                        "❌ MyCPAssistant error",
                        "Could not generate files: ${ex.message}",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Converts an arbitrary problem name into a valid Java/Kotlin identifier.
     *
     * "A. Watermelon"        → "Watermelon"
     * "1234A - Lucky Number" → "LuckyNumber"
     * "Two Sum"              → "TwoSum"
     */
    fun sanitizeName(rawName: String): String {
        // Drop leading contest-label patterns like "A.", "1234A -", "Problem B:"
        val stripped = rawName
            .replace(Regex("""^\w+[.\-:]\s*"""), "")  // drop "A. " / "A - " / "A:"
            .trim()
            .ifEmpty { rawName.trim() }                 // fall back to original if nothing left

        return stripped
            .split(Regex("""[\s\-_.,!?]+"""))           // split on common separators
            .filter { it.isNotBlank() }
            .joinToString("") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }
            .replace(Regex("""[^A-Za-z0-9_$]"""), "_") // ensure valid identifier chars
            .let { if (it.first().isDigit()) "_$it" else it } // identifiers can't start with digit
            .ifEmpty { "Solution" }
    }

    /**
     * Expands all `${'$'}{PLACEHOLDER}` tokens in the template string.
     */
    private fun expandTemplate(template: String, safeName: String, problem: ProblemData): String =
        template
            .replace("\${PROBLEM_NAME}",  safeName)
            .replace("\${DATE}",          LocalDate.now().toString())
            .replace("\${TIME_LIMIT}",    problem.timeLimit.toString())
            .replace("\${MEMORY_LIMIT}",  problem.memoryLimit.toString())
            .replace("\${URL}",           problem.url)
            .replace("\${GROUP}",         problem.group)

    /**
     * Creates (or overwrites) a file inside [parentVDir] using the VFS so
     * IntelliJ's indexer picks it up immediately.
     */
    private fun writeFile(parentVDir: VirtualFile, name: String, content: String): VirtualFile {
        val existing = parentVDir.findChild(name)
        val vFile    = existing ?: parentVDir.createChildData(this, name)
        VfsUtil.saveText(vFile, content)
        return vFile
    }

    private fun showNotification(
        project: Project,
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    /**
     * Registers [dir] as a source root in the first available module so that
     * IntelliJ's compiler includes it in the build and the ▶️ run config works.
     *
     * - Idempotent: does nothing if the folder is already a source root.
     * - Safe: wrapped in [ModuleRootModificationUtil.updateModel] inside the
     *   existing [WriteCommandAction], so no extra write lock is needed.
     */
    private fun ensureSourceRoot(project: Project, dir: VirtualFile) {
        val module = ModuleManager.getInstance(project).modules.firstOrNull() ?: return
        ModuleRootModificationUtil.updateModel(module) { model ->
            // Find the content entry whose root contains our directory
            val entry = model.contentEntries.firstOrNull { e ->
                dir.url.startsWith(e.url)
            } ?: model.addContentEntry(dir) // create one if needed

            // Only add if not already registered
            if (entry.sourceFolders.none { it.url == dir.url }) {
                entry.addSourceFolder(dir.url, /* isTestSource = */ false)
                log.info("ProblemFileGenerator: registered '${dir.path}' as source root")
            }
        }
    }
}
