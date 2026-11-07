package zovo.aiusage.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** Application settings, saved in aiUsageMeter.xml in the IDE config folder. */
@Service(Service.Level.APP)
@State(name = "ZovoAiUsageMeter", storages = [Storage("aiUsageMeter.xml")])
class UsageSettings : PersistentStateComponent<UsageSettings.S> {
    class S {
        var showClaude = true
        var showCodex = true
        var showGemini = true
        var showTokens = false
        var refreshSeconds = 60
        var warnAt = 90
        var notify = true
        var findClaudeAccounts = true
        /** Extra Claude Code config folders, one per line. */
        var extraClaudeDirs = ""
        /** Codex home folder; empty means CODEX_HOME or ~/.codex. */
        var codexHome = ""
        /** Gemini CLI tmp folder; empty means ~/.gemini/tmp. */
        var geminiDir = ""
    }

    private var s = S()

    override fun getState(): S = s

    override fun loadState(state: S) {
        XmlSerializerUtil.copyBean(state, s)
    }

    companion object {
        fun get(): UsageSettings = ApplicationManager.getApplication().getService(UsageSettings::class.java)

        fun lines(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
