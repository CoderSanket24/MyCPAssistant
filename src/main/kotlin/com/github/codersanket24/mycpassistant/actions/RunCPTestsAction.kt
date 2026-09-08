package com.github.codersanket24.mycpassistant.actions

import com.github.codersanket24.mycpassistant.toolWindow.CPTestToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * **Tools → Run CP Tests** (`Ctrl+Shift+F10`)
 *
 * Shows the CP Tests tool window and triggers **▶ Run All** on the panel.
 * The panel itself handles reading inputs from its text areas and running the tests,
 * so this action is just a keyboard-shortcut convenience wrapper.
 *
 * The action is disabled when no CP solution file (one with a sibling `_input1.txt`)
 * is active in the editor.
 */
class RunCPTestsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project    = e.project ?: return
        val sourceFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val baseName   = sourceFile.nameWithoutExtension
        val parent     = sourceFile.parent ?: return

        // Show tool window and load test cases for the current file
        val panel = CPTestToolWindowFactory.showAndGetPanel(project) ?: return
        panel.loadFromFile(sourceFile, baseName)

        // Small delay so the panel's loadFromFile has time to render,
        // then fire Run All automatically
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            panel.onRunAllPublic()
        }
    }

    override fun update(e: AnActionEvent) {
        val sourceFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val parent     = sourceFile?.parent
        val hasTests   = e.project != null &&
                sourceFile != null &&
                parent != null &&
                parent.findChild("${sourceFile.nameWithoutExtension}_input1.txt") != null
        e.presentation.isEnabledAndVisible = hasTests
    }
}
