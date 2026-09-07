package com.github.codersanket24.mycpassistant.actions

import com.github.codersanket24.mycpassistant.generator.ProblemFileGenerator
import com.github.codersanket24.mycpassistant.model.ProblemData
import com.github.codersanket24.mycpassistant.settings.AppSettingsState
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

/**
 * **Tools → Create CP File** (`Ctrl+Alt+N` / `Cmd+Option+N`)
 *
 * Allows the user to manually trigger solution-file generation without
 * the Competitive Companion browser extension.  A simple input dialog
 * collects the problem name; the rest of the metadata is left at sensible
 * defaults.
 */
class CreateCPFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val rawName = Messages.showInputDialog(
            project,
            "Enter the problem name (e.g. \"A. Watermelon\"):",
            "Create CP File",
            Messages.getQuestionIcon(),
            "",
            object : InputValidator {
                override fun checkInput(input: String?) = !input.isNullOrBlank()
                override fun canClose(input: String?)   = !input.isNullOrBlank()
            },
        ) ?: return  // user cancelled

        val settings = AppSettingsState.getInstance()
        val problem  = ProblemData(
            name        = rawName.trim(),
            group       = "Manual",
            url         = "",
            timeLimit   = 2000L,
            memoryLimit = 256,
            tests       = emptyList(),
        )

        ProblemFileGenerator.generate(project, problem)
    }

    /** Disable the action when no project is open. */
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
