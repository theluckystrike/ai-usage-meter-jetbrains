package zovo.aiusage.ide

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import zovo.aiusage.core.Aggregate
import zovo.aiusage.core.ClaudeLimits
import zovo.aiusage.core.Prices
import zovo.aiusage.core.Roots
import zovo.aiusage.core.StatusView
import zovo.aiusage.core.UsageStore
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Reads the local usage files on a background thread every N seconds and keeps the newest summary.
 * Nothing here runs on the EDT; listeners are called on the EDT after each refresh. No network calls.
 */
@Service(Service.Level.APP)
class UsageService : Disposable {
    class Snapshot(
        val summary: Aggregate.Summary,
        val view: StatusView.View,
        val files: Int,
        val roots: Roots.Resolved,
        val updatedAt: Long,
        val error: String?,
    )

    @Volatile var snapshot: Snapshot? = null
        private set

    private val lock = Any()
    private val store = UsageStore(historyDays = HISTORY_DAYS)
    private val home: String = System.getProperty("zovo.aiusage.home")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
    private val limitsReader = ClaudeLimits.Reader(home)
    private val table = Prices.buildTable()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    /** Alert ids already shown. Saved in the IDE so a restart does not show the same limit warning again. */
    private val notified: LinkedHashSet<String> = try {
        StatusView.parseShown(PropertiesComponent.getInstance().getValue(SHOWN_KEY))
    } catch (_: Exception) {
        LinkedHashSet()
    }
    private var future: ScheduledFuture<*>? = null
    private var interval = 0

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners.add(l)
        Disposer.register(parent) { listeners.remove(l) }
    }

    @Synchronized
    fun ensureStarted() {
        val want = UsageSettings.get().state.refreshSeconds.coerceIn(15, 3600)
        if (future != null && interval == want) return
        future?.cancel(false)
        interval = want
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ refreshNow() }, 0, want.toLong(), TimeUnit.SECONDS)
    }

    /** Settings changed: read again now with the new folders and interval. */
    fun settingsChanged() {
        synchronized(this) { future?.cancel(false); future = null }
        ensureStarted()
    }

    /** Refresh soon on a pooled thread (for the Refresh link). */
    fun refreshAsync() {
        AppExecutorUtil.getAppExecutorService().execute { refreshNow() }
    }

    private fun env(): Map<String, String> {
        // The demo sandbox (-Dzovo.aiusage.home) reads only the process environment; a normal IDE also gets the
        // login shell's variables, so CLAUDE_CONFIG_DIR set in ~/.zshrc works when the IDE starts from the Dock.
        if (System.getProperty("zovo.aiusage.home") != null) return System.getenv()
        val m = HashMap(System.getenv())
        try { m.putAll(EnvironmentUtil.getEnvironmentMap()) } catch (_: Exception) { }
        return m
    }

    fun refreshNow() {
        try {
            synchronized(lock) { refreshLocked() }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.info("AI Usage Meter: refresh failed", e)
            val old = snapshot
            if (old != null) snapshot = Snapshot(old.summary, old.view, old.files, old.roots, old.updatedAt, e.toString())
        }
        fire()
    }

    private fun folders(text: String, last: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val p = Roots.expandHome(t, home)
        return listOf(if (Paths.get(p).fileName?.toString() == last) p else Paths.get(p, last).toString())
    }

    private fun refreshLocked() {
        val st = UsageSettings.get().state
        val settings = Roots.Settings(
            extraClaudeConfigs = UsageSettings.lines(st.extraClaudeDirs),
            codexRoots = folders(st.codexHome, "sessions"),
            geminiRoots = st.geminiDir.trim().let { if (it.isEmpty()) emptyList() else listOf(it) },
            findClaudeAccounts = st.findClaudeAccounts,
        )
        val roots = Roots.resolve(settings, env(), home)
        val tools = Roots.Tools(st.showClaude, st.showCodex, st.showGemini)
        val now = System.currentTimeMillis()
        val list = Roots.listFiles(roots, tools, now - HISTORY_DAYS * UsageStore.DAY_MS)
        store.update(list, now, geminiNames = if (st.showGemini) Roots.geminiProjectNames(roots.gemini) else null)
        val claudeLimits = if (st.showClaude) limitsReader.read(roots.claudeConfigs) else emptyList()
        val summary = Aggregate.summarize(store.entries(), Aggregate.Options(now, table, 30, tools, store.limits, claudeLimits))
        val view = StatusView.build(summary, StatusView.Options(tools = tools, showTokens = st.showTokens, warnAt = st.warnAt.coerceIn(1, 100)))
        snapshot = Snapshot(summary, view, list.size, roots, now, null)
        var shown = false
        if (st.notify) for (a in view.alerts) {
            if (!notified.add(a.id)) continue
            while (notified.size > 200) notified.remove(notified.first())
            shown = true
            NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification("AI usage limit", a.text, NotificationType.WARNING)
                .notify(null)
        }
        if (shown) try { PropertiesComponent.getInstance().setValue(SHOWN_KEY, StatusView.formatShown(notified)) } catch (_: Exception) { }
    }

    private fun fire() {
        if (listeners.isEmpty()) return
        ApplicationManager.getApplication().invokeLater { for (l in listeners) l() }
    }

    override fun dispose() {
        synchronized(this) { future?.cancel(false); future = null }
    }

    companion object {
        const val HISTORY_DAYS = 31
        const val GROUP = "AI Usage Meter"
        private const val SHOWN_KEY = "zovo.aiusage.shownAlerts"
        private val LOG = Logger.getInstance(UsageService::class.java)

        fun get(): UsageService = ApplicationManager.getApplication().getService(UsageService::class.java)
    }
}
