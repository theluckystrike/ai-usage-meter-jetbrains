package zovo.aiusage.core

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Claude Code plan limits, read offline (port of claudeLimits.js). Claude Code saves its own last reading of
 * your plan usage as `cachedUsageUtilization` in its config file: ~/.claude.json for the default folder,
 * <CLAUDE_CONFIG_DIR>/.claude.json otherwise. Only that key is kept: never the account id, the email or
 * anything else from the file. No network call.
 */
object ClaudeLimits {
    const val MAX_BYTES = 64L * 1024 * 1024
    private const val KEY = "\"cachedUsageUtilization\""

    /** Known windows and their short names, in display order. Unknown keys are skipped. */
    val WINDOWS = listOf(
        Triple("five_hour", "5h", "5-hour"),
        Triple("seven_day", "wk", "weekly"),
        Triple("seven_day_opus", "wk Opus", "weekly Opus"),
        Triple("seven_day_sonnet", "wk Sonnet", "weekly Sonnet"),
    )

    data class Window(val key: String, val name: String, val label: String, val usedPercent: Double, val resetsAt: Long?)
    data class Limits(val fetchedAt: Long?, val windows: List<Window>)
    data class Account(val account: String, val fetchedAt: Long?, val windows: List<Window>)

    /** The config files Claude Code may use for a config folder, most likely first. */
    fun configFiles(dir: String, home: String): List<String> {
        val out = mutableListOf(Paths.get(dir, ".claude.json").toString())
        if (Paths.get(dir).toAbsolutePath().normalize() == Paths.get(home, ".claude").toAbsolutePath().normalize()) out.add(0, Paths.get(home, ".claude.json").toString())
        return out
    }

    private val MODEL_NAME = Regex("^[\\w .-]{1,40}$")

    /** Parsed config object -> limits, or null. */
    fun parse(obj: JsonObject?): Limits? {
        val c = obj?.obj("cachedUsageUtilization") ?: return null
        val u = c.obj("utilization") ?: return null
        val windows = ArrayList<Window>()
        for ((key, name, label) in WINDOWS) {
            val w = u.obj(key) ?: continue
            val v = w.number("utilization") ?: continue
            windows.add(Window(key, name, label, v.coerceIn(0.0, 100.0), timeMs(w.get("resets_at"))))
        }
        // Newer Claude Code versions also save a `limits` list. Team plans leave seven_day empty and keep their
        // weekly limit there, scoped to one model, so read it for anything the windows above did not give.
        val list = u.arr("limits")
        if (list != null) {
            val have = windows.map { it.key }.toMutableSet()
            for (el in list) {
                val l = el.asObj() ?: continue
                val pct = l.number("percent") ?: continue
                val kind = l.str("kind")
                val t: Triple<String, String, String> = when (kind) {
                    "session" -> WINDOWS[0]
                    "weekly_all" -> WINDOWS[1]
                    "weekly_scoped" -> {
                        val m = l.obj("scope")?.obj("model")?.str("display_name")
                        if (m == null || !MODEL_NAME.matches(m)) continue
                        Triple("seven_day_" + m.lowercase().replace(Regex("[^a-z0-9]+"), "_"), "wk $m", "weekly $m")
                    }
                    else -> continue
                }
                if (t.first in have) continue
                have.add(t.first)
                windows.add(Window(t.first, t.second, t.third, pct.coerceIn(0.0, 100.0), timeMs(l.get("resets_at"))))
            }
        }
        if (windows.isEmpty()) return null
        return Limits(timeMs(c.get("fetchedAtMs")), windows)
    }

    /** A window for display at [now]: null percent when the window has reset since Claude Code saved it. */
    fun windowView(w: Window, now: Long): WindowView {
        val reset = w.resetsAt != null && w.resetsAt <= now
        return WindowView(
            w.key, w.name, w.label,
            if (reset) null else Format.jsRound(w.usedPercent),
            w.resetsAt, w.resetsAt?.let { maxOf(0L, it - now) }, reset,
        )
    }

    /** Reads limits for config folders, re-reading a file only when its size or time changed. */
    class Reader(private val home: String) {
        private data class Cached(val size: Long, val mtime: Long, val limits: Limits?)
        private val cache = HashMap<String, Cached>()
        var reads = 0
            private set

        @Synchronized
        fun readFile(file: String): Limits? {
            val p: Path = Paths.get(file)
            val st = try { Files.readAttributes(p, BasicFileAttributes::class.java) } catch (_: Exception) { cache.remove(file); return null }
            if (!st.isRegularFile || st.size() == 0L || st.size() > MAX_BYTES) return null
            val mt = st.lastModifiedTime().toMillis()
            val old = cache[file]
            if (old != null && old.size == st.size() && old.mtime == mt) return old.limits
            var limits: Limits? = null
            try {
                reads++
                val text = Files.readString(p)
                if (text.contains(KEY)) limits = parse(parseJson(text).asObj())
            } catch (_: Exception) {
                limits = null // half-written or not JSON: try again next time
            }
            cache[file] = Cached(st.size(), mt, limits)
            return limits
        }

        /** Returns one reading per config folder, newest reading first. */
        fun read(configs: List<Roots.ClaudeConfig>): List<Account> {
            val out = ArrayList<Account>()
            for (c in configs) {
                var best: Limits? = null
                for (f in configFiles(c.dir, home)) {
                    val l = readFile(f) ?: continue
                    if (best == null || (l.fetchedAt ?: 0) > (best.fetchedAt ?: 0)) best = l
                }
                if (best != null) out.add(Account(c.label, best.fetchedAt, best.windows))
            }
            return out.sortedByDescending { it.fetchedAt ?: 0 }
        }
    }
}
