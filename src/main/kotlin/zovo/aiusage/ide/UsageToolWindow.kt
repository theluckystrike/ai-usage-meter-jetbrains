package zovo.aiusage.ide

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.BrowserLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import zovo.aiusage.core.Aggregate
import zovo.aiusage.core.ClaudeLimits
import zovo.aiusage.core.Format
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/** Links shown in the tool window and the plugin description. PayPal is left out until funding.json has it. */
object Support {
    const val REPO = "https://github.com/theluckystrike/ai-usage-meter-jetbrains"
    const val SPONSORS = "https://github.com/sponsors/theluckystrike"
    const val PROFILE = "https://github.com/theluckystrike"
    val MORE = listOf(
        "Context Drift for JetBrains" to "https://plugins.jetbrains.com/plugin/35875",
        "AI Usage Meter for VS Code" to "https://marketplace.visualstudio.com/items?itemName=zovo.ai-usage-meter",
        "Claude Code & Codex History Viewer for VS Code" to "https://marketplace.visualstudio.com/items?itemName=zovo.agent-session-viewer",
        "JSONL Viewer for VS Code" to "https://marketplace.visualstudio.com/items?itemName=zovo.zovo-jsonl-viewer",
    )
}

private class ReadOnlyModel(cols: Array<String>) : DefaultTableModel(cols, 0) {
    override fun isCellEditable(row: Int, column: Int) = false
}

class UsagePanel(private val project: Project) : com.intellij.openapi.Disposable {
    private val root = JPanel()
    val component: JComponent
    private val status = JBLabel("Reading local usage files…")
    private val tiles = JPanel(GridLayout(1, 3, JBUI.scale(8), 0))
    private val claudeModel = ReadOnlyModel(arrayOf("Account", "Limit", "Used", "Resets in", "Resets at", "Saved"))
    private val codexModel = ReadOnlyModel(arrayOf("Window", "Used", "Resets in", "Resets at", "Plan"))
    private val daysModel = ReadOnlyModel(arrayOf("Day", "Claude Code", "Codex", "Gemini CLI", "Total tokens", "Est. cost"))
    private val modelsModel = ReadOnlyModel(arrayOf("Tool", "Model", "Tokens", "Est. cost"))
    private val claudeEmpty = JBLabel()
    private val codexEmpty = JBLabel()
    private val time = SimpleDateFormat("HH:mm")

    init {
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(8, 12)
        val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0))
        head.add(status)
        head.add(ActionLink("Refresh") { UsageService.get().refreshAsync() })
        head.add(ActionLink("Settings") { ShowSettingsUtil.getInstance().showSettingsDialog(project, UsageConfigurable::class.java) })
        add(head)
        add(title("Today"))
        add(tiles)
        add(title("Claude Code plan limits, per account"))
        add(claudeEmpty)
        add(table(claudeModel))
        add(title("Codex rate limits"))
        add(codexEmpty)
        add(table(codexModel))
        add(title("Last 30 days"))
        add(table(daysModel))
        add(title("Models, last 30 days"))
        add(table(modelsModel))
        add(title("Privacy"))
        add(note("Keeps only token counts, model ids, times and limit numbers from local files. Does not keep or show prompt text. Never reads login tokens. No network calls. Costs are estimates at API prices."))
        add(title("Support and more tools"))
        val links = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0))
        links.add(BrowserLink("Source and issues on GitHub", Support.REPO))
        links.add(BrowserLink("Sponsor on GitHub", Support.SPONSORS))
        add(links)
        val more = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0))
        more.add(JBLabel("More from Zovo:"))
        for ((name, url) in Support.MORE) more.add(BrowserLink(name, url))
        more.add(BrowserLink("All Zovo tools", Support.PROFILE))
        add(more)
        root.add(Box.createVerticalGlue())
        component = JBScrollPane(root).apply { border = JBUI.Borders.empty() }
        val service = UsageService.get()
        service.addListener(this) { update() }
        service.ensureStarted()
        update()
    }

    private fun add(c: JComponent) {
        c.alignmentX = JComponent.LEFT_ALIGNMENT
        root.add(c)
    }

    private fun title(text: String): JComponent = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        border = JBUI.Borders.empty(12, 0, 4, 0)
    }

    private fun note(text: String): JComponent = JBLabel("<html>${text}</html>").apply { foreground = UIUtil.getContextHelpForeground() }

    private fun table(model: DefaultTableModel): JComponent {
        val t = JBTable(model)
        t.setShowGrid(false)
        t.rowSelectionAllowed = true
        val p = JPanel(BorderLayout())
        p.add(t.tableHeader, BorderLayout.NORTH)
        p.add(t, BorderLayout.CENTER)
        p.border = JBUI.Borders.customLine(JBColor.border(), 1)
        return p
    }

    private fun tile(name: String, t: Aggregate.Totals): JComponent {
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
        p.border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(8, 10))
        p.add(JBLabel(name).apply { font = font.deriveFont(Font.BOLD) })
        p.add(JBLabel("${Format.tokens(t.total)} tokens").apply { font = font.deriveFont(font.size2D + 6f) })
        p.add(JBLabel("${Format.money(t.cost)} estimated"))
        p.add(JBLabel("in ${Format.tokens(t.input)} · out ${Format.tokens(t.output)} · cache ${Format.tokens(t.cacheWrite + t.cacheRead)}").apply { foreground = UIUtil.getContextHelpForeground() })
        return p
    }

    private fun update() {
        val snap = UsageService.get().snapshot ?: return
        val s = snap.summary
        val now = s.now
        status.text = "Updated ${time.format(Date(snap.updatedAt))} · ${snap.files} files in the last ${UsageService.HISTORY_DAYS} days" + (snap.error?.let { " · last read failed: $it" } ?: "")
        tiles.removeAll()
        val st = UsageSettings.get().state
        if (st.showClaude) tiles.add(tile("Claude Code", s.today.getValue("claude")))
        if (st.showCodex) tiles.add(tile("Codex", s.today.getValue("codex")))
        if (st.showGemini) tiles.add(tile("Gemini CLI", s.today.getValue("gemini")))
        tiles.revalidate(); tiles.repaint()

        claudeModel.rowCount = 0
        for (a in s.claudeLimits) for (w in a.windows) {
            val v = ClaudeLimits.windowView(w, now)
            claudeModel.addRow(arrayOf(
                a.account, v.label, v.usedPercent?.let { "$it%" } ?: "reset since saved",
                if (v.reset) "" else v.resetsIn?.let { Format.duration(it) } ?: "",
                if (v.reset) "" else v.resetsAt?.let { Format.clock(it) } ?: "",
                a.fetchedAt?.let { if (now - it < 60000) "just now" else Format.duration(now - it) + " ago" } ?: "",
            ))
        }
        claudeEmpty.text = if (s.claudeLimits.isEmpty()) "No saved plan limits found. Claude Code saves them in .claude.json while you use it." else ""
        claudeEmpty.isVisible = s.claudeLimits.isEmpty()

        codexModel.rowCount = 0
        val l = s.limits
        if (l != null) for (w in listOf(l.primary, l.secondary)) {
            val v = Format.limitView(w, now) ?: continue
            codexModel.addRow(arrayOf(v.name, v.usedPercent?.let { "$it%" } ?: "reset since written", if (v.reset) "" else v.resetsIn?.let { Format.duration(it) } ?: "", if (v.reset) "" else v.resetsAt?.let { Format.clock(it) } ?: "", l.plan))
        }
        codexEmpty.text = if (l == null) "Codex has not written rate limits yet." else ""
        codexEmpty.isVisible = l == null

        daysModel.rowCount = 0
        for (d in s.perDay.reversed()) {
            daysModel.addRow(arrayOf(
                d.day, Format.tokens(d.byTool.getValue("claude").total), Format.tokens(d.byTool.getValue("codex").total),
                Format.tokens(d.byTool.getValue("gemini").total), Format.tokens(d.all.total), Format.money(d.all.cost),
            ))
        }
        modelsModel.rowCount = 0
        for (m in s.perModel) {
            modelsModel.addRow(arrayOf(Aggregate.TOOL_NAMES[m.tool] ?: m.tool, m.name, Format.tokens(m.total), if (m.priced) Format.money(m.cost) else "no price"))
        }
        root.revalidate(); root.repaint()
    }

    override fun dispose() {}
}
