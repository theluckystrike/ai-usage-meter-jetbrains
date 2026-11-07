package zovo.aiusage.core

import java.time.ZoneId

/** Status bar text and tooltip (port of statusbar.js). Pure, so tests can check them without the IDE. */
object StatusView {
    data class Options(
        val tools: Roots.Tools = Roots.Tools(),
        /** Show today's tokens and cost for every tool, not only for tools without a limit reading. */
        val showTokens: Boolean = false,
        val showCost: Boolean = true,
        val showBlock: Boolean = true,
        val showLimits: Boolean = true,
        val warnAt: Int = 90,
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    /** A limit window at or above the warning level, for a notification once per window. */
    data class Alert(val id: String, val text: String)

    data class View(val text: String, val tooltipLines: List<String>, val warn: Boolean, val alerts: List<Alert>) {
        /** Tooltip as simple HTML for the IDE. Lines starting with "**" are headings; "- " lines are items. */
        fun tooltipHtml(): String {
            val sb = StringBuilder("<html><body>")
            for (l in tooltipLines) {
                when {
                    l.isEmpty() -> sb.append("<br>")
                    l.startsWith("**") -> {
                        val end = l.indexOf("**", 2)
                        sb.append("<b>").append(esc(l.substring(2, end))).append("</b>").append(esc(l.substring(end + 2))).append("<br>")
                    }
                    l.startsWith("- ") -> sb.append("&nbsp;&bull; ").append(esc(l.substring(2))).append("<br>")
                    else -> sb.append(esc(l)).append("<br>")
                }
            }
            return sb.append("</body></html>").toString()
        }
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun ago(ms: Long): String = if (ms < 60000) "just now" else Format.duration(ms) + " ago"

    fun build(s: Aggregate.Summary, o: Options = Options()): View {
        val F = Format
        val tools = o.tools
        val seg = linkedMapOf("claude" to "", "codex" to "", "gemini" to "")
        val hasLimit = mutableMapOf("claude" to false, "codex" to false)
        val md = ArrayList<String>()
        val alerts = ArrayList<Alert>()
        var warn = false
        val now = s.now
        fun tok(t: Aggregate.Totals) = F.tokens(t.total) + if (o.showCost) " " + F.money(t.cost) else ""
        md.add("**Today** (since local midnight)"); md.add("")
        if (tools.claude) {
            val t = s.today.getValue("claude")
            md.add("- Claude Code: ${F.tokens(t.total)} tokens, ${F.money(t.cost)} est. (in ${F.tokens(t.input)} · out ${F.tokens(t.output)} · cache write ${F.tokens(t.cacheWrite)} · cache read ${F.tokens(t.cacheRead)})")
        }
        if (tools.codex) {
            val t = s.today.getValue("codex")
            md.add("- Codex: ${F.tokens(t.total)} tokens, ${F.money(t.cost)} est. (in ${F.tokens(t.input)} · out ${F.tokens(t.output)} · cache read ${F.tokens(t.cacheRead)})")
        }
        val gem = s.today.getValue("gemini")
        val showGemini = tools.gemini && gem.total > 0
        if (showGemini) {
            md.add("- Gemini CLI: ${F.tokens(gem.total)} tokens, ${F.money(gem.cost)} est. (in ${F.tokens(gem.input)} · out ${F.tokens(gem.output)} · cache read ${F.tokens(gem.cacheRead)})")
        }
        // Claude Code plan limits that Claude Code saved itself. The newest reading goes in the status bar.
        // Other accounts only while one of their windows is still open (at most 4 in all).
        val claudeShort = ArrayList<String>()
        val cl = (if (tools.claude && o.showLimits) s.claudeLimits else emptyList())
            .filterIndexed { i, a -> i == 0 || a.windows.any { w -> !(w.resetsAt != null && w.resetsAt <= now) } }.take(4)
        var has5h = false
        if (cl.isNotEmpty()) {
            md.add(""); md.add("**Claude Code plan limits** (as Claude Code last saved them)"); md.add("")
            cl.forEachIndexed { i, acct ->
                val head = if (cl.size > 1) "${acct.account}: " else ""
                for (w in acct.windows) {
                    val v = ClaudeLimits.windowView(w, now)
                    val pct = v.usedPercent
                    if (pct == null) { md.add("- $head${v.label}: window has reset since Claude Code saved it"); continue }
                    if (i == 0 && (v.key == "five_hour" || v.key == "seven_day")) { claudeShort.add("${v.name} $pct%"); if (v.key == "five_hour") has5h = true }
                    if (pct >= 90) warn = true
                    if (pct >= o.warnAt) alerts.add(Alert("claude|${acct.account}|${v.key}|${v.resetsAt ?: 0}", "Claude Code ${if (cl.size > 1) acct.account + " " else ""}${v.label} limit: $pct% used" + (v.resetsIn?.let { ", resets in ${F.duration(it)}" } ?: "")))
                    md.add("- $head${v.label}: $pct% used" + (if (v.resetsIn != null && v.resetsAt != null) ", resets in ${F.duration(v.resetsIn)} (${F.clock(v.resetsAt, o.zone)})" else ""))
                }
                if (acct.fetchedAt != null && acct.fetchedAt != 0L) md.add("- ${head}saved ${ago(now - acct.fetchedAt)}. Claude Code updates it while you use it.")
            }
        }
        if (claudeShort.isNotEmpty()) hasLimit["claude"] = true
        var blockText = ""
        if (tools.claude && o.showBlock) {
            md.add(""); md.add("**Claude Code 5-hour block**"); md.add("")
            val b = s.block
            if (b != null) {
                if (!has5h) blockText = "block ${F.duration(b.resetsInMs)} left"
                md.add("- ${F.tokens(b.totals.total)} tokens, ${F.money(b.totals.cost)} est. since ${F.clock(b.start, o.zone)}")
                md.add("- Resets at ${F.clock(b.end, o.zone)}, in ${F.duration(b.resetsInMs)}. Worked out from local timestamps.")
            } else {
                md.add("- No active block. One starts with your next Claude Code message.")
            }
        }
        val codexShort = ArrayList<String>()
        if (tools.codex && o.showLimits) {
            val l = s.limits
            md.add(""); md.add("**Codex rate limits** (from the newest session file)"); md.add("")
            if (l != null) {
                for (w in listOf(l.primary, l.secondary)) {
                    val v = F.limitView(w, now) ?: continue
                    val pct = v.usedPercent
                    if (pct == null) { md.add("- ${v.name}: window has reset since Codex last wrote it"); continue }
                    codexShort.add("${if (v.name == "week") "wk" else v.name} $pct%")
                    if (pct >= 90) warn = true
                    if (pct >= o.warnAt) alerts.add(Alert("codex|${v.name}|${v.resetsAt ?: 0}", "Codex ${v.name} limit: $pct% used" + (v.resetsIn?.let { ", resets in ${F.duration(it)}" } ?: "")))
                    md.add("- ${v.name}: $pct% used" + (if (v.resetsIn != null && v.resetsAt != null) ", resets in ${F.duration(v.resetsIn)} (${F.clock(v.resetsAt, o.zone)})" else ""))
                }
                if (l.plan.isNotEmpty()) md.add("- Plan: ${l.plan}")
            } else {
                md.add("- Codex has not written rate limits yet.")
            }
        }
        if (codexShort.isNotEmpty()) hasLimit["codex"] = true
        // One segment per tool: "Claude 5h 34% wk 61% · Codex 5h 43% wk 71%". Tokens and cost show when asked
        // for, or when a tool has no limit reading to show.
        if (tools.claude) {
            val parts = mutableListOf("Claude")
            if (o.showTokens || !hasLimit.getValue("claude")) parts.add(tok(s.today.getValue("claude")))
            parts.addAll(claudeShort)
            if (blockText.isNotEmpty()) parts.add(blockText)
            seg["claude"] = parts.joinToString(" ")
        }
        if (tools.codex) {
            val parts = mutableListOf("Codex")
            if (o.showTokens || !hasLimit.getValue("codex")) parts.add(tok(s.today.getValue("codex")))
            parts.addAll(codexShort)
            seg["codex"] = parts.joinToString(" ")
        }
        if (showGemini) seg["gemini"] = "Gemini " + tok(gem)
        md.add(""); md.add("Costs are estimates from the built-in price table (prices taken on ${Prices.PRICES_TAKEN_ON}). Click to open the AI Usage tool window.")
        val text = seg.values.filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "AI usage" }
        return View(text, md, warn, alerts)
    }
}
