package zovo.aiusage.core

import com.google.gson.JsonObject

/**
 * Usage-only readers for Claude Code, Codex and Gemini CLI session records (port of usage.js).
 * They read token counts, model ids, timestamps, the working folder and Codex rate limits. They never copy
 * prompt, reply, tool or reasoning text out of a record, and they skip record types they do not know.
 */

/** One usage record. tool is "claude", "codex" or "gemini". */
class Entry(
    var key: String = "",
    val ts: Long,
    val model: String,
    var cwd: String = "",
    override val input: Double,
    override val output: Double,
    override val cacheWrite: Double,
    override val cacheRead: Double,
) : Tokens {
    var tool: String = ""
    var file: String = ""
    var project: String = ""
    var account: String = ""
    val sum: Double get() = input + output + cacheWrite + cacheRead
}

/** One Codex rate-limit window. */
data class LimitWindow(val usedPercent: Double, val resetsAt: Long?, val windowMinutes: Double?)

/** Codex rate limits from one token_count event. */
data class CodexLimits(val ts: Long, val primary: LimitWindow?, val secondary: LimitWindow?, val hasCredits: Boolean?, val unlimited: Boolean?, val plan: String)

class CodexState {
    var model = ""
    var cwd = ""
    var prev: Quad? = null
}

data class Quad(val input: Double, val cached: Double, val cacheWrite: Double, val output: Double)

object Usage {
    private const val CLAUDE_HINT = "\"usage\""
    private val CODEX_HINTS = listOf("\"token_count\"", "\"turn_context\"", "\"session_meta\"")

    /** Quick text checks before parsing: most lines (prompts, replies, tool output) are never parsed at all. */
    fun claudeWorth(line: String) = line.contains(CLAUDE_HINT) && line.contains("\"assistant\"")
    fun codexWorth(line: String) = CODEX_HINTS.any { line.contains(it) }

    fun cleanModel(m: String?): String = if (!m.isNullOrEmpty() && !m.startsWith("<")) m.take(80) else ""

    /** One Claude Code record -> a usage entry, or null. The key dedupes the same API message written more than once. */
    fun claudeEntry(rec: JsonObject?, fallbackKey: String): Entry? {
        if (rec == null || rec.str("type") != "assistant") return null
        val msg = rec.obj("message") ?: return null
        val u = msg.obj("usage") ?: return null
        val ts = timeMs(rec.get("timestamp")) ?: return null
        if (msg.str("model") == "<synthetic>") return null
        val id = msg.str("id")
        val key = if (!id.isNullOrEmpty()) id + ":" + (rec.str("requestId") ?: "") else fallbackKey
        val e = Entry(
            key = key, ts = ts, model = cleanModel(msg.str("model")), cwd = rec.str("cwd") ?: "",
            input = num(u.get("input_tokens")), output = num(u.get("output_tokens")),
            cacheWrite = num(u.get("cache_creation_input_tokens")), cacheRead = num(u.get("cache_read_input_tokens")),
        )
        if (e.input == 0.0 && e.output == 0.0 && e.cacheWrite == 0.0 && e.cacheRead == 0.0) return null
        return e
    }

    private fun readTotals(t: JsonObject?): Quad? {
        if (t == null) return null
        return Quad(num(t.get("input_tokens")), num(t.get("cached_input_tokens")), num(t.get("cache_write_input_tokens")), num(t.get("output_tokens")))
    }

    private fun readWindow(w: JsonObject?, eventTs: Long): LimitWindow? {
        if (w == null) return null
        val used = w.number("used_percent") ?: return null
        var resetsAt: Long? = null
        val ra = w.number("resets_at")
        val rin = w.number("resets_in_seconds")
        if (ra != null) resetsAt = if (ra < 1e12) Math.round(ra * 1000) else Math.round(ra)
        else if (rin != null) resetsAt = eventTs + Math.round(rin * 1000)
        return LimitWindow(used.coerceIn(0.0, 100.0), resetsAt, w.number("window_minutes"))
    }

    /** Codex rate limits from a token_count payload. Null when both windows are missing. */
    fun codexLimits(rl: JsonObject?, ts: Long): CodexLimits? {
        if (rl == null) return null
        val primary = readWindow(rl.obj("primary"), ts)
        val secondary = readWindow(rl.obj("secondary"), ts)
        if (primary == null && secondary == null) return null
        val credits = rl.obj("credits")
        return CodexLimits(ts, primary, secondary, credits?.isTrue("has_credits"), credits?.isTrue("unlimited"), (rl.str("plan_type") ?: "").take(40))
    }

    class Step(val entry: Entry?, val limits: CodexLimits?)

    /**
     * One Codex record. Updates [state] and returns the token entry (the growth of total_token_usage since the
     * last token_count in the same file, so repeated events are not counted twice) and any rate limits.
     */
    fun codexStep(rec: JsonObject?, state: CodexState): Step {
        val none = Step(null, null)
        if (rec == null) return none
        val p = rec.obj("payload") ?: return none
        val type = rec.str("type")
        if (type == "session_meta") {
            val cwd = p.str("cwd")
            if (cwd != null && state.cwd.isEmpty()) state.cwd = cwd
            return none
        }
        if (type == "turn_context") {
            val m = cleanModel(p.str("model"))
            if (m.isNotEmpty()) state.model = m
            val cwd = p.str("cwd")
            if (cwd != null && state.cwd.isEmpty()) state.cwd = cwd
            return none
        }
        if (type != "event_msg" || p.str("type") != "token_count") return none
        val ts = timeMs(rec.get("timestamp")) ?: return none
        val limits = codexLimits(p.obj("rate_limits"), ts)
        val info = p.obj("info") ?: return Step(null, limits)
        val total = readTotals(info.obj("total_token_usage"))
        var d: Quad?
        if (total != null) {
            val prev = state.prev
            d = if (prev == null) total else {
                val g = Quad(total.input - prev.input, total.cached - prev.cached, total.cacheWrite - prev.cacheWrite, total.output - prev.output)
                if (g.input < 0 || g.cached < 0 || g.output < 0 || g.cacheWrite < 0) readTotals(info.obj("last_token_usage")) ?: Quad(0.0, 0.0, 0.0, 0.0) else g
            }
            state.prev = total
        } else {
            d = readTotals(info.obj("last_token_usage"))
        }
        if (d == null) return Step(null, limits)
        val cached = minOf(d.cached, d.input)
        val model = state.model.ifEmpty { cleanModel(info.str("model")) }
        val e = Entry(
            ts = ts, model = model, cwd = state.cwd,
            input = maxOf(0.0, d.input - cached), output = maxOf(0.0, d.output),
            cacheWrite = maxOf(0.0, d.cacheWrite), cacheRead = maxOf(0.0, cached),
        )
        return Step(if (e.sum > 0) e else null, limits)
    }

    /**
     * Gemini CLI session file text (JSON with a messages array, or JSONL with one record per line) -> entries.
     * Gemini counts cached tokens inside `input` and keeps thinking tokens apart from `output`, so cached tokens
     * move to cacheRead and thinking tokens are added to output (they are billed as output).
     */
    fun geminiEntries(text: String?): List<Entry> {
        val t = text?.trim() ?: return emptyList()
        if (t.isEmpty()) return emptyList()
        var msgs: List<JsonObject?>? = null
        val whole = parseJson(t).asObj()
        val arr = whole?.arr("messages")
        if (arr != null) msgs = arr.map { it.asObj() }
        if (msgs == null) {
            val list = ArrayList<JsonObject?>()
            for (line in t.split('\n')) {
                if (!line.contains("\"tokens\"")) continue
                val v = parseJson(line) ?: continue
                list.add(v.asObj())
            }
            msgs = list
        }
        val byId = LinkedHashMap<String, Entry>()
        var n = 0
        for (m in msgs) {
            n++
            if (m == null || m.str("type") != "gemini") continue
            val k = m.obj("tokens") ?: continue
            val ts = timeMs(m.get("timestamp")) ?: continue
            val cached = minOf(num(k.get("cached")), num(k.get("input")))
            val e = Entry(
                ts = ts, model = cleanModel(m.str("model")),
                input = maxOf(0.0, num(k.get("input")) - cached) + num(k.get("tool")),
                output = num(k.get("output")) + num(k.get("thoughts")),
                cacheWrite = 0.0, cacheRead = cached,
            )
            if (e.input == 0.0 && e.output == 0.0 && e.cacheRead == 0.0) continue
            val id = m.str("id")
            byId[if (!id.isNullOrEmpty()) id else "#$n"] = e // a message saved again replaces the old copy
        }
        return byId.values.toList()
    }
}
