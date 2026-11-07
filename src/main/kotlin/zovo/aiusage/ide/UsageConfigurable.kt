package zovo.aiusage.ide

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Settings | Tools | AI Usage Meter. */
class UsageConfigurable : Configurable {
    private val claude = JBCheckBox("Claude Code")
    private val codex = JBCheckBox("Codex")
    private val gemini = JBCheckBox("Gemini CLI")
    private val tokens = JBCheckBox("Show today's tokens and cost in the status bar for every tool")
    private val refresh = JSpinner(SpinnerNumberModel(60, 15, 3600, 15))
    private val warnAt = JSpinner(SpinnerNumberModel(90, 1, 100, 1))
    private val notify = JBCheckBox("Show a notification once per limit window when it reaches the warning level")
    private val accounts = JBCheckBox("Find other Claude Code config folders (~/.claude-*) in the home folder")
    private val extra = JBTextArea(3, 40)
    private val codexHome = JBTextField()
    private val geminiDir = JBTextField()
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "AI Usage Meter"

    override fun createComponent(): JComponent {
        val tools = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { add(claude); add(codex); add(gemini) }
        val p = FormBuilder.createFormBuilder()
            .addLabeledComponent("Show:", tools)
            .addComponent(tokens)
            .addLabeledComponent("Refresh every (seconds):", refresh)
            .addLabeledComponent("Warn at (% of a limit):", warnAt)
            .addComponent(notify)
            .addComponent(accounts)
            .addLabeledComponent("Extra Claude Code config folders (one per line):", JBScrollPane(extra), true)
            .addLabeledComponent("Codex home folder (empty: CODEX_HOME or ~/.codex):", codexHome)
            .addLabeledComponent("Gemini CLI tmp folder (empty: ~/.gemini/tmp):", geminiDir)
            .addComponent(JBLabel("<html>Keeps only token counts, model ids, times and limit numbers. No prompt text kept, no login tokens, no network.</html>").apply { foreground = UIUtil.getContextHelpForeground() })
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = p
        reset()
        return p
    }

    private val s get() = UsageSettings.get().state

    override fun isModified(): Boolean {
        val st = s
        return claude.isSelected != st.showClaude || codex.isSelected != st.showCodex || gemini.isSelected != st.showGemini ||
            tokens.isSelected != st.showTokens || (refresh.value as Int) != st.refreshSeconds || (warnAt.value as Int) != st.warnAt ||
            notify.isSelected != st.notify || accounts.isSelected != st.findClaudeAccounts || extra.text.trim() != st.extraClaudeDirs ||
            codexHome.text.trim() != st.codexHome || geminiDir.text.trim() != st.geminiDir
    }

    override fun apply() {
        val st = s
        st.showClaude = claude.isSelected
        st.showCodex = codex.isSelected
        st.showGemini = gemini.isSelected
        st.showTokens = tokens.isSelected
        st.refreshSeconds = refresh.value as Int
        st.warnAt = warnAt.value as Int
        st.notify = notify.isSelected
        st.findClaudeAccounts = accounts.isSelected
        st.extraClaudeDirs = extra.text.trim()
        st.codexHome = codexHome.text.trim()
        st.geminiDir = geminiDir.text.trim()
        UsageService.get().settingsChanged()
    }

    override fun reset() {
        val st = s
        claude.isSelected = st.showClaude
        codex.isSelected = st.showCodex
        gemini.isSelected = st.showGemini
        tokens.isSelected = st.showTokens
        refresh.value = st.refreshSeconds.coerceIn(15, 3600)
        warnAt.value = st.warnAt.coerceIn(1, 100)
        notify.isSelected = st.notify
        accounts.isSelected = st.findClaudeAccounts
        extra.text = st.extraClaudeDirs
        codexHome.text = st.codexHome
        geminiDir.text = st.geminiDir
    }

    override fun disposeUIResources() {
        panel = null
    }
}
