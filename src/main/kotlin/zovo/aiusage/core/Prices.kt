package zovo.aiusage.core

/**
 * Estimated prices in US dollars per million tokens (port of prices.js). The longest model-id prefix that
 * matches wins. Built-in values were taken on [PRICES_TAKEN_ON] from the vendors' published API prices.
 * Subscription plans are not billed per token, so for them the number is what the same tokens would cost on the API.
 */
object Prices {
    const val PRICES_TAKEN_ON = "2026-11-06"

    data class Row(val input: Double, val output: Double, val cacheWrite: Double, val cacheRead: Double, val source: String = "built-in")

    val DEFAULT: Map<String, Row> = linkedMapOf(
        "claude-fable-5-1" to Row(10.0, 50.0, 12.5, 0.25),
        "claude-mythos-5-1" to Row(10.0, 50.0, 12.5, 0.25),
        "claude-fable-5" to Row(10.0, 50.0, 12.5, 1.0),
        "claude-opus-5-5" to Row(4.0, 20.0, 5.0, 0.2),
        "claude-opus-5" to Row(5.0, 25.0, 6.25, 0.5),
        "claude-opus-4-8" to Row(5.0, 25.0, 6.25, 0.5),
        "claude-opus-4-7" to Row(5.0, 25.0, 6.25, 0.5),
        "claude-opus-4-6" to Row(5.0, 25.0, 6.25, 0.5),
        "claude-opus-4-5" to Row(5.0, 25.0, 6.25, 0.5),
        "claude-opus-4" to Row(15.0, 75.0, 18.75, 1.5),
        "claude-sonnet-5-5" to Row(2.0, 10.0, 2.5, 0.2),
        "claude-sonnet-5" to Row(2.0, 10.0, 2.5, 0.2),
        "claude-sonnet-4" to Row(3.0, 15.0, 3.75, 0.3),
        "claude-haiku-5-5" to Row(0.1, 0.5, 0.125, 0.01),
        "claude-haiku-4-5" to Row(1.0, 5.0, 1.25, 0.1),
        "claude-3-5-haiku" to Row(0.8, 4.0, 1.0, 0.08),
        "gpt-6.1-sol" to Row(2.0, 10.0, 0.0, 0.1),
        "gpt-6.1-terra" to Row(1.0, 5.0, 0.0, 0.05),
        "gpt-5.3-codex" to Row(1.75, 14.0, 0.0, 0.175),
        "gpt-5.2-codex" to Row(1.75, 14.0, 0.0, 0.175),
        "gpt-5.1-codex-mini" to Row(0.25, 2.0, 0.0, 0.025),
        "gpt-5.1-codex" to Row(1.25, 10.0, 0.0, 0.125),
        "gpt-5-codex" to Row(1.25, 10.0, 0.0, 0.125),
        "gpt-5" to Row(1.25, 10.0, 0.0, 0.125),
        // Gemini: Google's paid-tier API prices for prompts up to 200k tokens. Thinking tokens count as output.
        "gemini-3-pro" to Row(2.0, 12.0, 0.0, 0.2),
        "gemini-3-flash" to Row(0.5, 3.0, 0.0, 0.05),
        "gemini-2.5-pro" to Row(1.25, 10.0, 0.0, 0.125),
        "gemini-2.5-flash-lite" to Row(0.1, 0.4, 0.0, 0.01),
        "gemini-2.5-flash" to Row(0.3, 2.5, 0.0, 0.03),
    )

    class Table(val rows: Map<String, Row>, val ignored: List<String>)

    /** Built-in table plus user rows (user rows win). A user row with no number is ignored and listed. */
    fun buildTable(user: Map<String, Map<String, Double?>> = emptyMap()): Table {
        val table = LinkedHashMap(DEFAULT)
        val ignored = ArrayList<String>()
        for ((k, v) in user) {
            val key = k.trim().lowercase()
            val vals = listOf("input", "output", "cacheWrite", "cacheRead").map { v[it] }
            if (key.isEmpty() || vals.all { it == null }) { ignored.add(k); continue }
            fun ok(d: Double?) = if (d != null && d.isFinite() && d >= 0) d else 0.0
            table[key] = Row(ok(vals[0]), ok(vals[1]), ok(vals[2]), ok(vals[3]), "settings")
        }
        return Table(table, ignored)
    }

    private val SEP = Regex("[-.@:_]")

    /** The price row for a model id: exact, then the longest matching prefix. Strips "provider/" prefixes. */
    fun priceFor(table: Table, model: String?): Pair<String, Row>? {
        if (model.isNullOrEmpty()) return null
        var m = model.lowercase()
        if (m.contains('/')) m = m.substring(m.lastIndexOf('/') + 1)
        if (m.startsWith("anthropic.")) m = m.substring("anthropic.".length)
        table.rows[m]?.let { return m to it }
        var best: String? = null
        for (k in table.rows.keys) {
            if (m.startsWith(k) && (m.length == k.length || SEP.matches(m[k.length].toString())) && (best == null || k.length > best.length)) best = k
        }
        return best?.let { it to table.rows.getValue(it) }
    }

    /** Cost in dollars for token counts, or null when the model has no price row. */
    fun costOf(table: Table, model: String?, t: Tokens): Double? {
        val r = priceFor(table, model)?.second ?: return null
        return (t.input * r.input + t.output * r.output + t.cacheWrite * r.cacheWrite + t.cacheRead * r.cacheRead) / 1e6
    }
}

/** Token counts of one entry. Doubles, like JavaScript numbers, so both versions add up the same way. */
interface Tokens {
    val input: Double
    val output: Double
    val cacheWrite: Double
    val cacheRead: Double
}
