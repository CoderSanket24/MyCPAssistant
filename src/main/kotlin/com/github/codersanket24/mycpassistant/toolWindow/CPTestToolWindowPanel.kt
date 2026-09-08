package com.github.codersanket24.mycpassistant.toolWindow

import com.github.codersanket24.mycpassistant.runner.CPTestRunner
import com.github.codersanket24.mycpassistant.runner.TestResult
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.CompoundBorder

/**
 * CPH / KHelper–style test runner panel for the **"CP Tests"** right tool window.
 *
 * Layout (right sidebar):
 * ```
 * ┌─────────────────────────────────────┐
 * │  CF2259D           [▶ Run All]      │  ← header
 * ├─────────────────────────────────────┤
 * │ ┌─────────────────────────────────┐ │
 * │ │ Test 1          ● [▶]  (42 ms)  │ │  ← card header
 * │ │ Input:                          │ │
 * │ │ ┌─────────────────────────────┐ │ │
 * │ │ │ 4                           │ │ │  ← editable
 * │ │ └─────────────────────────────┘ │ │
 * │ │ Expected:                       │ │
 * │ │ ┌─────────────────────────────┐ │ │
 * │ │ │ YES                         │ │ │  ← editable
 * │ │ └─────────────────────────────┘ │ │
 * │ │ Output:  ✓ PASS  42 ms         │ │
 * │ │ ┌─────────────────────────────┐ │ │
 * │ │ │ YES               (green)   │ │ │  ← read-only, colored
 * │ │ └─────────────────────────────┘ │ │
 * │ └─────────────────────────────────┘ │
 * │  ... more cards ...                 │
 * ├─────────────────────────────────────┤
 * │          [+ Add Test Case]          │  ← footer
 * └─────────────────────────────────────┘
 * ```
 */
class CPTestToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    // ── Colors ─────────────────────────────────────────────────────────────
    private val passColor    = JBColor(Color(0, 150, 0),   Color(100, 220, 100))
    private val failColor    = JBColor(Color(200, 0, 0),   Color(255, 100, 100))
    private val errorColor   = JBColor(Color(200, 100, 0), Color(220, 140, 60))
    private val timeoutColor = JBColor(Color(160, 130, 0), Color(220, 190, 60))
    private val passBorder   = JBColor(Color(100, 200, 100), Color(60, 140, 60))
    private val failBorder   = JBColor(Color(220, 100, 100), Color(180, 60, 60))

    // ── State ──────────────────────────────────────────────────────────────
    private var currentFile: VirtualFile? = null
    private val cards = mutableListOf<TestCaseCard>()

    // ── Header ─────────────────────────────────────────────────────────────
    private val problemLabel = JBLabel("Open a CP solution file").apply {
        font = font.deriveFont(Font.BOLD, 13f)
        horizontalAlignment = SwingConstants.LEFT
    }
    private val runAllButton = JButton("▶ Run All").apply {
        font = font.deriveFont(Font.BOLD)
        toolTipText = "Compile and run all test cases (reads inputs from the boxes below)"
        addActionListener { onRunAll() }
    }

    // ── Cards container (scrollable) ───────────────────────────────────────
    private val cardsBox = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4, 6, 0, 6)
    }

    // ── Init ───────────────────────────────────────────────────────────────

    init {
        // Header
        val header = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(6, 8)
            background = UIUtil.getPanelBackground()
            add(problemLabel, BorderLayout.CENTER)
            add(runAllButton, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)

        // Scrollable cards area
        val scroll = JBScrollPane(cardsBox).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBar.unitIncrement = 16
        }
        add(scroll, BorderLayout.CENTER)

        // Footer: "+ Add Test Case"
        val footer = JPanel(FlowLayout(FlowLayout.CENTER, 0, 4)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
            val addBtn = JButton("＋ Add Test Case").apply {
                addActionListener { addCard("", "") }
            }
            add(addBtn)
        }
        add(footer, BorderLayout.SOUTH)
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Loads test cases from the filesystem and rebuilds cards.
     * Call this when a new problem is parsed (from [ProblemFileGenerator]).
     */
    fun loadFromFile(sourceFile: VirtualFile, problemName: String) {
        currentFile = sourceFile
        problemLabel.text = problemName

        val parent = sourceFile.parent ?: return
        val base   = sourceFile.nameWithoutExtension

        clearCards()

        // Discover _inputN.txt / _outputN.txt pairs
        var n = 1
        while (true) {
            val inputFile = parent.findChild("${base}_input$n.txt") ?: break
            val outFile   = parent.findChild("${base}_output$n.txt")
            val inputText = com.intellij.openapi.vfs.VfsUtil.loadText(inputFile)
            val expText   = outFile?.let { com.intellij.openapi.vfs.VfsUtil.loadText(it) } ?: ""
            addCard(inputText, expText)
            n++
        }

        if (cards.isEmpty()) addCard("", "") // always show at least one blank card
        revalidate(); repaint()
    }

    /** Updates cards with run results. Called on EDT after [CPTestRunner.runAll]. */
    fun showResults(results: List<TestResult>) {
        runAllButton.isEnabled = true
        results.forEach { r ->
            cards.getOrNull(r.index - 1)?.showResult(r)
        }
    }

    /** Called by [com.github.codersanket24.mycpassistant.actions.RunCPTestsAction] via keyboard shortcut. */
    fun onRunAllPublic() = onRunAll()

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun clearCards() {
        cards.clear()
        cardsBox.removeAll()
    }

    private fun addCard(input: String, expected: String) {
        val idx  = cards.size + 1
        val card = TestCaseCard(idx, input, expected)
        cards.add(card)
        cardsBox.add(card)
        cardsBox.add(Box.createVerticalStrut(8))
        cardsBox.revalidate()
        cardsBox.repaint()
    }

    private fun removeCard(card: TestCaseCard) {
        val pos = cards.indexOf(card)
        if (pos < 0) return
        cards.removeAt(pos)
        // renumber remaining cards
        cards.forEachIndexed { i, c -> c.updateIndex(i + 1) }
        // rebuild cardsBox
        cardsBox.removeAll()
        cards.forEach { c ->
            cardsBox.add(c)
            cardsBox.add(Box.createVerticalStrut(8))
        }
        cardsBox.revalidate()
        cardsBox.repaint()
    }

    private fun onRunAll() {
        // If no file is loaded yet, try the current editor file
        val file = currentFile
            ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?: run {
                problemLabel.text = "No file open in editor."
                return
            }

        val base   = file.nameWithoutExtension
        val parent = file.parent

        // If still no cards, try to auto-load from disk
        if (cards.isEmpty() && parent != null) {
            loadFromFile(file, base)
        } else {
            currentFile = file
            problemLabel.text = base
        }

        if (cards.isEmpty()) {
            problemLabel.text = "No test cases found for '$base'."
            return
        }

        // Collect (input, expected) from each card's live text areas
        val testCases = cards.map { it.getInput() to it.getExpected() }

        // Disable button and show running state on each card
        runAllButton.isEnabled = false
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

        private val titleLabel  = JBLabel("Test $index").apply { font = font.deriveFont(Font.BOLD) }
        private val statusLabel = JBLabel("●").apply {
            foreground = JBColor.GRAY
            toolTipText = "Not yet run"
        }
        private val timeLabel   = JBLabel("").apply {
            foreground = UIUtil.getContextHelpForeground()
            font       = font.deriveFont(10f)
        }
        private val runOneBtn   = JButton("▶").apply {
            preferredSize = Dimension(38, 24)
            toolTipText   = "Run only this test case"
            addActionListener { onRunSingle() }
        }
        private val deleteBtn   = JButton("✕").apply {
            preferredSize = Dimension(28, 24)
            toolTipText   = "Remove this test case"
            foreground    = JBColor.GRAY
            addActionListener { removeCard(this@TestCaseCard) }
        }

        private val inputArea    = makeTextArea(inputText,    editable = true)
        private val expectedArea = makeTextArea(expectedText, editable = true)
        private val gotArea      = makeTextArea("",           editable = false)
        private val gotPanel     = buildGotPanel()  // hidden until first run

        init {
            background = UIUtil.getPanelBackground()
            border     = CompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(6),
            )
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

            // Card header row: [Test N] [●] [▶] [✕] ... [time]
            val cardHeader = JPanel(BorderLayout()).apply {
                isOpaque = false
                val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(titleLabel); add(statusLabel)
                }
                val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    isOpaque = false
                    add(timeLabel); add(runOneBtn); add(deleteBtn)
                }
                add(left,  BorderLayout.WEST)
                add(right, BorderLayout.EAST)
            }

            // Content
            val content = JPanel().apply {
                layout  = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(Box.createVerticalStrut(6))
                add(sectionLabel("Input:"))
                add(Box.createVerticalStrut(2))
                add(makeScrollPane(inputArea, rows = 3))
                add(Box.createVerticalStrut(6))
                add(sectionLabel("Expected:"))
                add(Box.createVerticalStrut(2))
                add(makeScrollPane(expectedArea, rows = 3))
                add(Box.createVerticalStrut(6))
                add(gotPanel)
            }

            add(cardHeader, BorderLayout.NORTH)
            add(content,    BorderLayout.CENTER)
        }

        // ── Accessors ───────────────────────────────────────────────────────
        fun getInput()    = inputArea.text
        fun getExpected() = expectedArea.text
        fun updateIndex(i: Int) { index = i; titleLabel.text = "Test $i" }

        // ── State changes ───────────────────────────────────────────────────
        fun setRunning() {
            statusLabel.text      = "⟳"
            statusLabel.foreground = JBColor.GRAY
            timeLabel.text        = ""
            gotPanel.isVisible    = false
        }

        fun showResult(r: TestResult) {
            val (emoji, color) = when (r.status) {
                TestResult.Status.PASS    -> "✓" to passColor
                TestResult.Status.FAIL    -> "✗" to failColor
                TestResult.Status.ERROR   -> "!" to errorColor
                TestResult.Status.TIMEOUT -> "⌛" to timeoutColor
            }
            statusLabel.text      = emoji
            statusLabel.foreground = color
            timeLabel.text        = "${r.timeMs} ms"

            gotArea.text = if (r.status == TestResult.Status.ERROR || r.status == TestResult.Status.TIMEOUT)
                (r.errorMsg.takeIf { it.isNotBlank() } ?: r.got).trim()
            else r.got.trim()

            // Colour the output border
            gotPanel.border = CompoundBorder(
                BorderFactory.createLineBorder(
                    if (r.status == TestResult.Status.PASS) passBorder else failBorder, 2, true
                ),
                JBUI.Borders.empty(4),
            )
            gotPanel.isVisible = true

            revalidate(); repaint()
        }

        // ── Run single ──────────────────────────────────────────────────────
        private fun onRunSingle() {
            val file = currentFile
                ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                ?: return
            currentFile = file
            problemLabel.text = file.nameWithoutExtension

            setRunning()
            runOneBtn.isEnabled = false

            CPTestRunner.runAll(
                project    = project,
                sourceFile = file,
                testCases  = listOf(getInput() to getExpected()),
                onComplete = { results ->
                    results.firstOrNull()?.let { r ->
                        // remap index to this card's index
                        showResult(r.copy(index = index))
                    }
                    runOneBtn.isEnabled = true
                },
            )
        }

        // ── UI helpers ──────────────────────────────────────────────────────
        private fun buildGotPanel() = JPanel(BorderLayout()).apply {
            isOpaque  = false
            isVisible = false
            val label = sectionLabel("Output:")
            border    = BorderFactory.createEmptyBorder()
            val inner = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(label,                       BorderLayout.NORTH)
                add(Box.createVerticalStrut(2),   BorderLayout.NORTH) // gap
                add(makeScrollPane(gotArea, rows = 3), BorderLayout.CENTER)
            }
            add(inner, BorderLayout.CENTER)
        }
    }

    // ── Shared UI factories ────────────────────────────────────────────────

    private fun sectionLabel(text: String) = JBLabel(text).apply {
        font       = font.deriveFont(Font.BOLD, 11f)
        foreground = UIUtil.getContextHelpForeground()
        alignmentX = LEFT_ALIGNMENT
    }

    private fun makeTextArea(text: String, editable: Boolean) = JBTextArea(text).apply {
        isEditable = editable
        font       = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap   = true
        wrapStyleWord = false
    }

    private fun makeScrollPane(area: JBTextArea, rows: Int) =
        JBScrollPane(area).apply {
            val lineH = area.getFontMetrics(area.font).height
            preferredSize = Dimension(Int.MAX_VALUE, lineH * rows + 8)
            maximumSize   = Dimension(Int.MAX_VALUE, lineH * rows + 8)
            minimumSize   = Dimension(100, lineH * 2)
            alignmentX    = LEFT_ALIGNMENT
        }
}
