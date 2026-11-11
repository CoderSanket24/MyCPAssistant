package com.github.codersanket24.mycpassistant.actions

import com.github.codersanket24.mycpassistant.toolWindow.CPTestToolWindowFactory
import com.github.codersanket24.mycpassistant.toolWindow.CPTestToolWindowPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager

/**
 * **Tools → Run CP Tests** (`Ctrl+Shift+F10`)
 *
 * Shows the CP Tests tool window and triggers **▶ Run All** on the panel.
 * The panel itself handles reading inputs from its text areas and running the tests.
 */
class RunCPTestsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project    = e.project ?: return
        val sourceFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val baseName   = sourceFile.nameWithoutExtension

        // Show tool window and load test cases for the current file
        val panel = CPTestToolWindowFactory.showAndGetPanel(project) ?: return
        panel.loadFromFile(sourceFile, baseName)

        // Run tests on the panel
        ApplicationManager.getApplication().invokeLater {
            panel.onRunAllPublic()
        }
    }

    override fun update(e: AnActionEvent) {
        val project    = e.project
        val sourceFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val hasTests   = project != null &&
                sourceFile != null &&
                CPTestToolWindowPanel.hasTestCases(project, sourceFile)
        e.presentation.isEnabledAndVisible = hasTests
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
