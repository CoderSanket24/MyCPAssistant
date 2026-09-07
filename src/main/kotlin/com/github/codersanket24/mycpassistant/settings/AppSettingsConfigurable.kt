package com.github.codersanket24.mycpassistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel

/**
 * Settings UI panel shown at **Settings › Tools › Custom CP Companion**.
 *
 * IntelliJ calls [createComponent] once to build the panel, then [isModified]
 * on each focus change, [apply] when the user clicks *OK / Apply*, and [reset]
 * when the user clicks *Cancel* or opens the page.
 */
class AppSettingsConfigurable : Configurable {

    // ── Swing controls ─────────────────────────────────────────────────────

    private val outputDirField  = JBTextField(40)
    private val portSpinner     = JSpinner(SpinnerNumberModel(27121, 1024, 65535, 1))
    private val extensionField  = JBTextField(10)
    private val templateArea    = JTextArea(18, 60).apply {
        lineWrap   = true
        wrapStyleWord = true
        font       = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
    }

    private var panel: JPanel? = null

    // ── Configurable ───────────────────────────────────────────────────────

    override fun getDisplayName(): String = "Custom CP Companion"

    override fun createComponent(): JComponent {
        val scrollableTemplate = JBScrollPane(templateArea).apply {
            preferredSize = java.awt.Dimension(600, 300)
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Output directory (relative to project root):"), outputDirField, 1, false)
            .addLabeledComponent(JBLabel("Listener port:"), portSpinner, 1, false)
            .addLabeledComponent(JBLabel("File extension (.java / .kt / .cpp):"), extensionField, 1, false)
            .addSeparator()
            .addComponent(JBLabel("Code template (placeholders: \${PROBLEM_NAME}, \${DATE}, \${GROUP}, \${URL}, \${TIME_LIMIT}, \${MEMORY_LIMIT}):"))
            .addComponent(scrollableTemplate, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset() // populate controls from current saved state
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = AppSettingsState.getInstance()
        return outputDirField.text.trim()  != s.outputDirectory     ||
               (portSpinner.value as Int)  != s.listenerPort        ||
               extensionField.text.trim()  != s.fileExtension       ||
               templateArea.text           != s.codeTemplate
    }

    override fun apply() {
        val s = AppSettingsState.getInstance()
        s.outputDirectory = outputDirField.text.trim()
        s.listenerPort    = portSpinner.value as Int
        s.fileExtension   = extensionField.text.trim().let { if (it.startsWith(".")) it else ".$it" }
        s.codeTemplate    = templateArea.text
    }

    override fun reset() {
        val s = AppSettingsState.getInstance()
        outputDirField.text       = s.outputDirectory
        portSpinner.value         = s.listenerPort
        extensionField.text       = s.fileExtension
        templateArea.text         = s.codeTemplate
    }

    override fun disposeUIResources() {
        panel = null
    }
}
