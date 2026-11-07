package zovo.aiusage.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Turns usage entries into the numbers the status bar and the tool window show (port of aggregate.js). Pure. */
object Aggregate {
    const val HOUR_MS = 60L * 60 * 1000
    const val BLOCK_MS = 5 * HOUR_MS
    val TOOLS = listOf("claude", "codex", "gemini")
    val TOOL_NAMES = mapOf("claude" to "Claude Code", "codex" to "Codex", "gemini" to "Gemini CLI")

    class Totals(val name: String = "") {
        var input = 0.0; var output = 0.0; var cacheWrite = 0.0; var cacheRead = 0.0
        var total = 0.0; var cost = 0.0; var unpriced = 0.0; var count = 0
        var tool = ""
        var priced = true
        val tools = ArrayList<String>()

        fun add(e: Entry, c: Double?) {
            input += e.input; output += e.output; cacheWrite += e.cacheWrite; cacheRead += e.cacheRead
            total += e.input + e.output + e.cacheWrite + e.cacheRead
            count++
            if (c == null) unpriced += e.input + e.output + e.cacheWrite + e.cacheRead else cost += c
        }
    }

    class Day(val day: String) {
        val all = Totals(day)
        val byTool = TOOLS.associateWith { Totals(it) }
    }

    class Block(val start: Long, val end: Long, val last: Long, val resetsInMs: Long, val totals: Totals, val perMinute: Long)

    class Summary(
        val now: Long,
        val days: Int,
        val today: Map<String, Totals>,
        val range: Totals,
        val perDay: List<Day>,
        val perProject: List<Totals>,
        val perModel: List<Totals>,
        val perTool: List<Totals>,
        val perAccount: List<Totals>,
        val block: Block?,
        val limits: CodexLimits?,
        val claudeLimits: List<ClaudeLimits.Account>,
        val unpricedModels: List<String>,
    )

    fun dayKey(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    /** Local midnight of the day [back] days before the day of [now]. */
    fun dayStart(now: Long, back: Long, zone: ZoneId): Long =
        LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).minusDays(back).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Claude Code 5-hour blocks: a block starts at the hour of the first message after a gap and lasts 5 hours.
     * Only local timestamps are used. Returns the active block or null.
     */
    fun claudeBlock(entries: List<Entry>, now: Long, table: Prices.Table): Block? {
        val list = entries.filter { it.tool == "claude" && it.ts <= now && it.ts > now - 3 * 24 * HOUR_MS }.sortedBy { it.ts }
        var start = 0L; var end = 0L; var last = 0L
        var totals: Totals? = null
        for (e in list) {
            if (totals == null || e.ts >= end || e.ts - last >= BLOCK_MS) {
                start = e.ts - Math.floorMod(e.ts, HOUR_MS)
                end = start + BLOCK_MS
                totals = Totals()
            }
            last = e.ts
            totals.add(e, Prices.costOf(table, e.model, e))
        }
        if (totals == null || now >= end) return null
        val elapsed = maxOf(1L, now - start)
        return Block(start, end, last, end - now, totals, Format.jsRound(totals.total / (elapsed / 60000.0)))
    }

    private fun sortRows(m: Map<String, Totals>): List<Totals> =
        m.values.sortedWith(compareByDescending<Totals> { it.cost }.thenByDescending { it.total }.thenBy { it.name })

    class Options(
        val now: Long,
        val table: Prices.Table,
        val days: Int = 30,
        val tools: Roots.Tools = Roots.Tools(),
        val limits: CodexLimits? = null,
        val claudeLimits: List<ClaudeLimits.Account> = emptyList(),
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    fun summarize(entries: List<Entry>, o: Options): Summary {
        val now = o.now
        val zone = o.zone
        val days = o.days.coerceIn(1, 3650)
        fun shown(tool: String) = when (tool) { "claude" -> o.tools.claude; "codex" -> o.tools.codex; "gemini" -> o.tools.gemini; else -> true }
        val all = entries.filter { shown(it.tool) }
        val todayStart = dayStart(now, 0, zone)
        val rangeStart = dayStart(now, (days - 1).toLong(), zone)
        val today = TOOLS.associateWith { Totals(TOOL_NAMES.getValue(it)) }
        val dayMap = LinkedHashMap<String, Day>()
        for (i in days - 1 downTo 0) {
            val k = dayKey(dayStart(now, i.toLong(), zone) + HOUR_MS * 12, zone)
            dayMap[k] = Day(k)
        }
        val projects = LinkedHashMap<String, Totals>()
        val models = LinkedHashMap<String, Totals>()
        val tools = LinkedHashMap<String, Totals>()
        val accounts = LinkedHashMap<String, Totals>()
        val unpriced = sortedSetOf<String>()
        val range = Totals("range")
        for (e in all) {
            if (e.ts < rangeStart || e.ts > now + HOUR_MS) continue
            val cost = Prices.costOf(o.table, e.model, e)
            if (cost == null) unpriced.add(e.model.ifEmpty { "(no model id)" })
            if (e.ts >= todayStart) today[e.tool]?.add(e, cost)
            range.add(e, cost)
            dayMap[dayKey(e.ts, zone)]?.let { d -> d.all.add(e, cost); d.byTool[e.tool]?.add(e, cost) }
            val pk = e.project.ifEmpty { "(unknown folder)" }
            val p = projects.getOrPut(pk) { Totals(pk) }
            p.add(e, cost)
            if (e.tool !in p.tools) p.tools.add(e.tool)
            val mname = e.model.ifEmpty { "(no model id)" }
            val m = models.getOrPut(e.tool + "|" + mname) { Totals(mname).also { it.tool = e.tool; it.priced = cost != null } }
            m.add(e, cost)
            tools.getOrPut(e.tool) { Totals(TOOL_NAMES[e.tool] ?: e.tool).also { it.tool = e.tool } }.add(e, cost)
            if (e.tool == "claude") {
                val ak = e.account.ifEmpty { "(default)" }
                accounts.getOrPut(ak) { Totals(ak) }.add(e, cost)
            }
        }
        return Summary(
            now = now, days = days, today = today, range = range, perDay = dayMap.values.toList(),
            perProject = sortRows(projects), perModel = sortRows(models), perTool = sortRows(tools), perAccount = sortRows(accounts),
            block = if (o.tools.claude) claudeBlock(all, now, o.table) else null,
            limits = if (o.tools.codex) o.limits else null,
            claudeLimits = if (o.tools.claude) o.claudeLimits else emptyList(),
            unpricedModels = unpriced.toList(),
        )
    }
}
