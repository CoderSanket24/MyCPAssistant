package com.github.codersanket24.mycpassistant.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registers the **"CP Tests"** tool window on the RIGHT stripe.
 *
 * The single content tab hosts a [CPTestToolWindowPanel].
 * Other parts of the plugin can call [getPanel] / [showAndGetPanel] to push
 * results into the UI without going through the action system.
 */
class CPTestToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel   = CPTestToolWindowPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    companion object {
        const val TOOL_WINDOW_ID = "CP Tests"

        fun getPanel(project: Project): CPTestToolWindowPanel? {
            val tw = com.intellij.openapi.wm.ToolWindowManager
                .getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID) ?: return null
            return tw.contentManager.getContent(0)?.component as? CPTestToolWindowPanel
        }

        fun showAndGetPanel(project: Project): CPTestToolWindowPanel? {
            val tw = com.intellij.openapi.wm.ToolWindowManager
                .getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID) ?: return null
            tw.show()
            return tw.contentManager.getContent(0)?.component as? CPTestToolWindowPanel
        }
    }
}
