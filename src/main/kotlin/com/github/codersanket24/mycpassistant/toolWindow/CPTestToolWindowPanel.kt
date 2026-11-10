package com.github.codersanket24.mycpassistant.toolWindow

import com.github.codersanket24.mycpassistant.runner.CPTestRunner
import com.github.codersanket24.mycpassistant.runner.TestResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.border.CompoundBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * CPH / KHelper–style test runner panel for the **"CP Tests"** right tool window.
 *
 * Features:
 * - Dynamic height text areas (no premature scrollbars)
 * - Auto-sync with the active editor tab (FileEditorManagerListener)
 * - Automatic persistence of custom/edited test cases to disk
 * - Status pills and pass/fail summary badge
 */
class CPTestToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = thisLogger()

    // ── Status Colors ───────────────────────────────────────────────────────
    private val passColor    = JBColor(Color(38, 166, 91),   Color(80, 200, 120))
    private val failColor    = JBColor(Color(217, 30, 24),   Color(240, 80, 80))
    private val errorColor   = JBColor(Color(230, 126, 34),  Color(243, 156, 18))
    private val timeoutColor = JBColor(Color(180, 130, 0),   Color(241, 196, 15))

    private val passBorder   = JBColor(Color(38, 166, 91),   Color(60, 160, 90))
    private val failBorder   = JBColor(Color(217, 30, 24),   Color(200, 60, 60))
    private val errorBorder  = JBColor(Color(230, 126, 34),  Color(210, 110, 30))

    // ── State ──────────────────────────────────────────────────────────────
    private var currentFile: VirtualFile? = null
    private val cards = mutableListOf<TestCaseCard>()

    // ── Header Widgets ─────────────────────────────────────────────────────
    private val problemLabel = JBLabel("No problem open").apply {
        font = font.deriveFont(Font.BOLD, 13f)
        horizontalAlignment = SwingConstants.LEFT
    }

    private val statsBadge = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        border = JBUI.Borders.empty(2, 6)
        isVisible = false
    }

    private val runAllButton = JButton("▶ Run All").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        margin = Insets(4, 10, 4, 10)
        toolTipText = "Compile and run all test cases"
        addActionListener { onRunAll() }
    }

    // ── Cards Container (Scrollable) ───────────────────────────────────────
    private val cardsBox = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(6, 8, 8, 8)
    }

    // ── Init ───────────────────────────────────────────────────────────────

    init {
        // Top Toolbar
        val header = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(8, 10)
            )
            background = UIUtil.getPanelBackground()

            val leftBox = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(problemLabel)
                add(statsBadge)
            }
            add(leftBox, BorderLayout.CENTER)
            add(runAllButton, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)

        // Center: Scrollable Cards
        val scrollPane = JBScrollPane(cardsBox).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)

        // Footer: "+ Add Test Case"
        val footer = JPanel(FlowLayout(FlowLayout.CENTER, 0, 6)).apply {
            background = UIUtil.getPanelBackground()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                JBUI.Borders.empty(4)
            )
            val addBtn = JButton("＋ Add Test Case").apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                margin = Insets(4, 12, 4, 12)
                toolTipText = "Add a new blank test case"
                addActionListener { addCard("", "") }
            }
            add(addBtn)
        }
        add(footer, BorderLayout.SOUTH)

        // Listen for active editor tab changes to auto-switch problems (CPH style)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val newFile = event.newFile ?: return
                    autoLoadIfSolution(newFile)
                }
            }
        )

        // Auto-load currently open file if available
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let {
            autoLoadIfSolution(it)
        }
    }

    override fun dispose() {
        // Resources cleaned up when tool window content is closed
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Loads test cases from the filesystem and populates cards.
     */
    fun loadFromFile(sourceFile: VirtualFile, problemName: String) {
        currentFile = sourceFile
        problemLabel.text = problemName
        statsBadge.isVisible = false

        val parent = sourceFile.parent ?: return
        val base   = sourceFile.nameWithoutExtension

        cards.clear()

        var n = 1
        while (true) {
            val inputFile = parent.findChild("${base}_input$n.txt") ?: break
            val outFile   = parent.findChild("${base}_output$n.txt")
            val inputText = VfsUtil.loadText(inputFile)
            val expText   = outFile?.let { VfsUtil.loadText(it) } ?: ""
            cards.add(TestCaseCard(n, inputText, expText))
            n++
        }

        if (cards.isEmpty()) {
            cards.add(TestCaseCard(1, "", ""))
        }

        rebuildCardsBox()
    }

    /** Updates cards with run results. Called on EDT after [CPTestRunner.runAll]. */
    fun showResults(results: List<TestResult>) {
        runAllButton.isEnabled = true
        results.forEach { r ->
            cards.getOrNull(r.index - 1)?.showResult(r)
        }

        val total = results.size
        val passed = results.count { it.status == TestResult.Status.PASS }
        statsBadge.text = "$passed / $total Passed"
        statsBadge.foreground = if (passed == total && total > 0) passColor else failColor
        statsBadge.isVisible = true

        revalidate()
        repaint()
    }

    /** Called by [com.github.codersanket24.mycpassistant.actions.RunCPTestsAction]. */
    fun onRunAllPublic() = onRunAll()

    // ── Internal Helpers ───────────────────────────────────────────────────

    private fun autoLoadIfSolution(file: VirtualFile) {
        if (file == currentFile) return
        val parent = file.parent ?: return
        val base   = file.nameWithoutExtension
        if (parent.findChild("${base}_input1.txt") != null) {
            loadFromFile(file, base)
        }
    }

    private fun persistCardsToDisk(sourceFile: VirtualFile) {
        val parent = sourceFile.parent ?: return
        val base   = sourceFile.nameWithoutExtension
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                cards.forEachIndexed { i, card ->
                    val n = i + 1
                    val inpFile = parent.findChild("${base}_input$n.txt")
                        ?: parent.createChildData(this, "${base}_input$n.txt")
                    val expFile = parent.findChild("${base}_output$n.txt")
                        ?: parent.createChildData(this, "${base}_output$n.txt")
                    VfsUtil.saveText(inpFile, card.getInput())
                    VfsUtil.saveText(expFile, card.getExpected())
                }
            } catch (ex: Exception) {
                log.warn("Failed to persist test cases to disk: ${ex.message}")
            }
        }
    }

    private fun addCard(input: String, expected: String) {
        val idx  = cards.size + 1
        val card = TestCaseCard(idx, input, expected)
        cards.add(card)
        rebuildCardsBox()
        currentFile?.let { persistCardsToDisk(it) }
    }

    private fun removeCard(card: TestCaseCard) {
        val oldSize = cards.size
        if (cards.size <= 1) {
            card.reset("", "")
            return
        }
        cards.remove(card)
        rebuildCardsBox()

        currentFile?.let { file ->
            val parent = file.parent ?: return@let
            val base   = file.nameWithoutExtension
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    parent.findChild("${base}_input$oldSize.txt")?.delete(this)
                    parent.findChild("${base}_output$oldSize.txt")?.delete(this)
                    persistCardsToDisk(file)
                } catch (ex: Exception) {
                    log.warn("Failed to delete test files: ${ex.message}")
                }
            }
        }
    }

    private fun rebuildCardsBox() {
        cardsBox.removeAll()
        cards.forEachIndexed { i, card ->
            card.updateIndex(i + 1)
            cardsBox.add(card)
            cardsBox.add(Box.createVerticalStrut(10))
        }
        cardsBox.add(Box.createVerticalGlue())
        cardsBox.revalidate()
        cardsBox.repaint()
    }

    private fun onRunAll() {
        val activeEditorFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val file = (if (activeEditorFile != null && activeEditorFile.parent?.findChild("${activeEditorFile.nameWithoutExtension}_input1.txt") != null) activeEditorFile else null)
            ?: currentFile
            ?: activeEditorFile
            ?: run {
                problemLabel.text = "No file open in editor"
                return
            }

        val base   = file.nameWithoutExtension
        val parent = file.parent

        if (cards.isEmpty() && parent != null) {
            loadFromFile(file, base)
        } else {
            currentFile = file
            problemLabel.text = base
        }

        if (cards.isEmpty()) {
            problemLabel.text = "No tests found for '$base'"
            return
        }

        // Persist any card edits to disk
        persistCardsToDisk(file)

        val testCases = cards.map { it.getInput() to it.getExpected() }

        runAllButton.isEnabled = false
        statsBadge.isVisible   = false
        cards.forEach { it.setRunning() }

        CPTestRunner.runAll(
            project    = project,
            sourceFile = file,
            testCases  = testCases,
            onStart    = {},
            onComplete = { results -> showResults(results) },
        )
    }

    // ── TestCaseCard ───────────────────────────────────────────────────────

    inner class TestCaseCard(
        private var index: Int,
        inputText: String,
        expectedText: String,
    ) : JPanel(BorderLayout()) {

        private val titleLabel  = JBLabel("Test #$index").apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }
        private val statusBadge = JBLabel("● Not run").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }
        private val timeLabel   = JBLabel("").apply {
            foreground = UIUtil.getContextHelpForeground()
            font       = font.deriveFont(10f)
        }
        private val runOneBtn   = JButton("▶").apply {
            preferredSize = Dimension(32, 22)
            margin        = Insets(0, 0, 0, 0)
            toolTipText   = "Run only this test case"
            addActionListener { onRunSingle() }
        }
        private val deleteBtn   = JButton("✕").apply {
            preferredSize = Dimension(24, 22)
            margin        = Insets(0, 0, 0, 0)
            toolTipText   = "Delete this test case"
            foreground    = JBColor.GRAY
            addActionListener { removeCard(this@TestCaseCard) }
        }

        // Text Areas
        private val inputArea    = makeTextArea(inputText,    editable = true)
        private val expectedArea = makeTextArea(expectedText, editable = true)
        private val gotArea      = makeTextArea("",           editable = false)

        private val inputScroll    = makeScrollPane(inputArea,    minLines = 2, maxLines = 8)
        private val expectedScroll = makeScrollPane(expectedArea, minLines = 2, maxLines = 8)
        private val gotScroll      = makeScrollPane(gotArea,      minLines = 2, maxLines = 10)

        private val gotHeaderLabel = sectionLabel("Output:")
        private val gotPanel       = JPanel().apply {
            layout   = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            isVisible = false
            alignmentX = Component.LEFT_ALIGNMENT

            add(gotHeaderLabel)
            add(Box.createVerticalStrut(3))
            add(gotScroll)
        }

        init {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
            border     = CompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(8, 10)
            )

            // Header Row: [Test #N] [Status Badge] ──── [Time] [▶] [✕]
            val cardHeader = JPanel(BorderLayout()).apply {
                isOpaque   = false
                alignmentX = Component.LEFT_ALIGNMENT

                val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    isOpaque = false
                    add(titleLabel)
                    add(statusBadge)
                }
                val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    isOpaque = false
                    add(timeLabel)
                    add(runOneBtn)
                    add(deleteBtn)
                }
                add(left,  BorderLayout.WEST)
                add(right, BorderLayout.EAST)
            }

            // Body Column
            val body = JPanel().apply {
                layout   = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT

                add(cardHeader)
                add(Box.createVerticalStrut(8))

                add(sectionLabel("Input:"))
                add(Box.createVerticalStrut(3))
                add(inputScroll)
                add(Box.createVerticalStrut(8))

                add(sectionLabel("Expected:"))
                add(Box.createVerticalStrut(3))
                add(expectedScroll)
                add(Box.createVerticalStrut(8))

                add(gotPanel)
            }

            add(body, BorderLayout.CENTER)

            bindAutoHeight(inputArea, inputScroll, 2, 8)
            bindAutoHeight(expectedArea, expectedScroll, 2, 8)
        }

        override fun getMaximumSize(): Dimension {
            return Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        // ── Accessors & Helpers ─────────────────────────────────────────────
        fun getInput()    = inputArea.text
        fun getExpected() = expectedArea.text

        fun updateIndex(i: Int) {
            index = i
            titleLabel.text = "Test #$i"
        }

        fun reset(input: String, expected: String) {
            inputArea.text    = input
            expectedArea.text = expected
            gotArea.text      = ""
            statusBadge.text  = "● Not run"
            statusBadge.foreground = JBColor.GRAY
            timeLabel.text    = ""
            gotPanel.isVisible = false
            revalidate()
            repaint()
        }

        fun setRunning() {
            statusBadge.text       = "⟳ Running..."
            statusBadge.foreground = JBColor.namedColor("ProgressBar.progressColor", JBColor(Color(0, 120, 215), Color(80, 160, 240)))
            timeLabel.text         = ""
            gotPanel.isVisible     = false
            revalidate()
            repaint()
        }

        fun showResult(r: TestResult) {
            when (r.status) {
                TestResult.Status.PASS -> {
                    statusBadge.text       = "✓ Passed"
                    statusBadge.foreground = passColor
                    gotHeaderLabel.text    = "Output: ✓ Matches expected"
                    gotHeaderLabel.foreground = passColor
                    gotScroll.border       = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(passBorder, 2, true),
                        JBUI.Borders.empty(2)
                    )
                }
                TestResult.Status.FAIL -> {
                    statusBadge.text       = "✗ Failed"
                    statusBadge.foreground = failColor
                    gotHeaderLabel.text    = "Output: ✗ Did not match"
                    gotHeaderLabel.foreground = failColor
                    gotScroll.border       = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(failBorder, 2, true),
                        JBUI.Borders.empty(2)
                    )
                }
                TestResult.Status.ERROR -> {
                    statusBadge.text       = "! Error"
                    statusBadge.foreground = errorColor
                    gotHeaderLabel.text    = "Error output:"
                    gotHeaderLabel.foreground = errorColor
                    gotScroll.border       = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(errorBorder, 2, true),
                        JBUI.Borders.empty(2)
                    )
                }
                TestResult.Status.TIMEOUT -> {
                    statusBadge.text       = "⌛ Timeout"
                    statusBadge.foreground = timeoutColor
                    gotHeaderLabel.text    = "Output before timeout:"
                    gotHeaderLabel.foreground = timeoutColor
                    gotScroll.border       = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(errorBorder, 2, true),
                        JBUI.Borders.empty(2)
                    )
                }
            }

            timeLabel.text = "${r.timeMs} ms"
            gotArea.text   = if (r.status == TestResult.Status.ERROR || r.status == TestResult.Status.TIMEOUT) {
                (r.errorMsg.takeIf { it.isNotBlank() } ?: r.got).trim()
            } else {
                r.got.trim()
            }

            updateScrollPaneHeight(gotArea, gotScroll, minLines = 2, maxLines = 10)
            gotPanel.isVisible = true

            this@CPTestToolWindowPanel.revalidate()
            this@CPTestToolWindowPanel.repaint()
        }

        private fun onRunSingle() {
            val file = currentFile
                ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                ?: return

            currentFile = file
            problemLabel.text = file.nameWithoutExtension

            persistCardsToDisk(file)

            setRunning()
            runOneBtn.isEnabled = false

            CPTestRunner.runAll(
                project    = project,
                sourceFile = file,
                testCases  = listOf(getInput() to getExpected()),
                onComplete = { results ->
                    results.firstOrNull()?.let { r ->
                        showResult(r.copy(index = index))
                    }
                    runOneBtn.isEnabled = true
                },
            )
        }
    }

    // ── Shared UI Factories ────────────────────────────────────────────────

    private fun sectionLabel(text: String) = JBLabel(text).apply {
        font       = font.deriveFont(Font.BOLD, 11f)
        foreground = UIUtil.getContextHelpForeground()
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun makeTextArea(text: String, editable: Boolean) = JBTextArea(text).apply {
        isEditable    = editable
        font          = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap      = false
        wrapStyleWord = false
        margin        = Insets(4, 6, 4, 6)
    }

    private fun makeScrollPane(area: JBTextArea, minLines: Int, maxLines: Int): JBScrollPane {
        val scroll = JBScrollPane(area).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(2)
            )
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy   = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            alignmentX                = Component.LEFT_ALIGNMENT
        }
        updateScrollPaneHeight(area, scroll, minLines, maxLines)
        return scroll
    }

    private fun updateScrollPaneHeight(area: JBTextArea, scroll: JBScrollPane, minLines: Int, maxLines: Int) {
        val lineCount = maxOf(minLines, minOf(maxLines, area.text.lines().size))
        val lineH     = maxOf(16, area.getFontMetrics(area.font).height)
        val h         = lineH * lineCount + 14
        scroll.preferredSize = Dimension(100, h)
        scroll.maximumSize   = Dimension(Int.MAX_VALUE, h)
        scroll.minimumSize   = Dimension(50, lineH * minLines + 14)
    }

    private fun bindAutoHeight(area: JBTextArea, scroll: JBScrollPane, minLines: Int, maxLines: Int) {
        area.document.addDocumentListener(object : DocumentListener {
            private fun update() {
                updateScrollPaneHeight(area, scroll, minLines, maxLines)
                this@CPTestToolWindowPanel.revalidate()
                this@CPTestToolWindowPanel.repaint()
            }
            override fun insertUpdate(e: DocumentEvent?) = update()
            override fun removeUpdate(e: DocumentEvent?) = update()
            override fun changedUpdate(e: DocumentEvent?) = update()
        })
    }
}
