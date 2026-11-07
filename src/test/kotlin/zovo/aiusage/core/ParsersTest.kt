package zovo.aiusage.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zovo.aiusage.core.TestSupport.NOW
import java.time.Instant

class ParsersTest {
    private fun o(s: String): JsonObject = JsonParser.parseString(s).asJsonObject
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    @Test fun claudeEntryKeepsCountsAndNoText() {
        val e = Usage.claudeEntry(o("""{"type":"assistant","timestamp":"${iso(NOW)}","cwd":"/w/p","requestId":"r1","message":{"id":"m1","model":"claude-opus-5-5","content":[{"type":"text","text":"SECRET"}],"usage":{"input_tokens":10,"output_tokens":20,"cache_creation_input_tokens":30,"cache_read_input_tokens":40}}}"""), "fb")!!
        assertEquals("m1:r1", e.key)
        assertEquals(NOW, e.ts)
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), listOf(e.input, e.output, e.cacheWrite, e.cacheRead))
        assertEquals("/w/p", e.cwd)
    }

    @Test fun claudeEntryRejectsOddRecords() {
        assertNull(Usage.claudeEntry(o("""{"type":"user","message":{"usage":{}}}"""), "x"))
        assertNull(Usage.claudeEntry(o("""{"type":"assistant","timestamp":"${iso(NOW)}","message":{"usage":"x"}}"""), "x"))
        assertNull(Usage.claudeEntry(o("""{"type":"assistant","timestamp":"nope","message":{"usage":{"input_tokens":1}}}"""), "x"))
        assertNull(Usage.claudeEntry(o("""{"type":"assistant","timestamp":"${iso(NOW)}","message":{"model":"<synthetic>","usage":{"input_tokens":1}}}"""), "x"))
        assertNull(Usage.claudeEntry(o("""{"type":"assistant","timestamp":"${iso(NOW)}","message":{"usage":{"input_tokens":0,"output_tokens":0}}}"""), "x"))
        val noId = Usage.claudeEntry(o("""{"type":"assistant","timestamp":"${iso(NOW)}","message":{"usage":{"input_tokens":1,"output_tokens":-5}}}"""), "file#3")!!
        assertEquals("file#3", noId.key)
        assertEquals(0.0, noId.output, 0.0)
        assertFalse(Usage.claudeWorth("""{"type":"user","message":{"content":"hi"}}"""))
        assertTrue(Usage.claudeWorth("""{"type":"assistant","message":{"usage":{}}}"""))
    }

    private fun tc(ts: Long, info: String, rl: String = "null") = o("""{"timestamp":"${iso(ts)}","type":"event_msg","payload":{"type":"token_count","info":$info,"rate_limits":$rl}}""")

    @Test fun codexCountsGrowthOnce() {
        val st = CodexState()
        Usage.codexStep(o("""{"type":"session_meta","payload":{"cwd":"/w/a","base_instructions":{"text":"SECRET"}}}"""), st)
        Usage.codexStep(o("""{"type":"turn_context","payload":{"model":"gpt-6.1-sol","cwd":"/other"}}"""), st)
        assertEquals("/w/a", st.cwd)
        assertEquals("gpt-6.1-sol", st.model)
        val a = Usage.codexStep(tc(NOW, """{"total_token_usage":{"input_tokens":100,"cached_input_tokens":60,"output_tokens":10}}"""), st).entry!!
        assertEquals(listOf(40.0, 10.0, 0.0, 60.0), listOf(a.input, a.output, a.cacheWrite, a.cacheRead))
        assertNull(Usage.codexStep(tc(NOW + 1, """{"total_token_usage":{"input_tokens":100,"cached_input_tokens":60,"output_tokens":10}}"""), st).entry)
        val b = Usage.codexStep(tc(NOW + 2, """{"total_token_usage":{"input_tokens":250,"cached_input_tokens":160,"output_tokens":25}}"""), st).entry!!
        assertEquals(listOf(50.0, 100.0, 15.0), listOf(b.input, b.cacheRead, b.output))
    }

    @Test fun codexTotalGoingDownFallsBackToLast() {
        val st = CodexState()
        Usage.codexStep(tc(NOW, """{"total_token_usage":{"input_tokens":1000,"cached_input_tokens":0,"output_tokens":100}}"""), st)
        val e = Usage.codexStep(tc(NOW + 1, """{"total_token_usage":{"input_tokens":50,"cached_input_tokens":0,"output_tokens":5},"last_token_usage":{"input_tokens":50,"cached_input_tokens":10,"output_tokens":5}}"""), st).entry!!
        assertEquals(listOf(40.0, 10.0, 5.0), listOf(e.input, e.cacheRead, e.output))
    }

    @Test fun codexRateLimitLine() {
        val st = CodexState()
        val line = """{"timestamp":"${iso(NOW)}","type":"event_msg","payload":{"type":"token_count","info":null,"rate_limits":{"primary":{"used_percent":42.5,"window_minutes":300,"resets_at":${NOW / 1000 + 600}},"secondary":{"used_percent":120,"window_minutes":10080,"resets_in_seconds":60},"plan_type":"pro","credits":{"has_credits":true}}}}"""
        assertTrue(Usage.codexWorth(line))
        val step = Usage.codexStep(Lines.parseLine(line), st)
        assertNull(step.entry)
        val l = step.limits!!
        assertEquals(42.5, l.primary!!.usedPercent, 0.0)
        assertEquals(NOW + 600000, l.primary!!.resetsAt)
        assertEquals("clamped", 100.0, l.secondary!!.usedPercent, 0.0)
        assertEquals(NOW + 60000, l.secondary!!.resetsAt)
        assertEquals("pro", l.plan)
        assertEquals(true, l.hasCredits)
        val v = Format.limitView(l.primary, NOW)!!
        assertEquals("5h", v.name)
        assertEquals(43L, v.usedPercent)
        assertNull("null windows give no limits", Usage.codexStep(tc(NOW, "null", """{"primary":null,"secondary":null,"credits":null,"plan_type":null}"""), st).limits)
    }

    @Test fun teamPlanWeeklyLimitFromTheLimitsList() {
        val raw = o("""{"cachedUsageUtilization":{"fetchedAtMs":1762480000000,"accountUuid":"x","utilization":{
          "five_hour":{"utilization":34,"resets_at":"2026-11-07T08:00:00Z"},"seven_day":null,"seven_day_opus":null,"seven_day_sonnet":null,
          "limits":[
            {"kind":"session","group":"session","percent":34,"severity":"normal","resets_at":"2026-11-07T08:00:00Z","scope":null,"is_active":true},
            {"kind":"weekly_scoped","group":"weekly","percent":15,"severity":"normal","resets_at":"2026-11-12T03:00:00Z","scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":false},
            {"kind":"mystery","percent":50},{"kind":"weekly_all","percent":"x"},null]}}}""")
        val l = ClaudeLimits.parse(raw)!!
        assertEquals(listOf("five_hour", "seven_day_fable"), l.windows.map { it.key })
        assertEquals(listOf("5h", "wk Fable"), l.windows.map { it.name })
        assertEquals(listOf(34.0, 15.0), l.windows.map { it.usedPercent })
        assertEquals(Instant.parse("2026-11-12T03:00:00Z").toEpochMilli(), l.windows[1].resetsAt)
        val both = ClaudeLimits.parse(o("""{"cachedUsageUtilization":{"utilization":{"seven_day":{"utilization":79,"resets_at":null},"limits":[{"kind":"weekly_all","percent":79},{"kind":"weekly_scoped","percent":100,"scope":{"model":{"display_name":"Fable"}}}]}}}"""))!!
        assertEquals(listOf("seven_day", "seven_day_fable"), both.windows.map { it.key })
        val onlyList = ClaudeLimits.parse(o("""{"cachedUsageUtilization":{"utilization":{"limits":[{"kind":"weekly_all","percent":9,"resets_at":"2026-11-12T03:00:00Z"}]}}}"""))!!
        assertEquals(listOf("seven_day"), onlyList.windows.map { it.key })
        assertEquals("wk", onlyList.windows[0].name)
        assertNull(ClaudeLimits.parse(o("""{"numStartups":3}""")))
    }

    @Test fun limitWindowThatResetShowsNoPercent() {
        val w = ClaudeLimits.Window("five_hour", "5h", "5-hour", 98.0, NOW - 1000)
        assertNull(ClaudeLimits.windowView(w, NOW).usedPercent)
        assertEquals(98L, ClaudeLimits.windowView(w.copy(resetsAt = NOW + 1000), NOW).usedPercent)
    }

    @Test fun geminiJsonAndJsonl() {
        val json = """{"messages":[{"id":"a","type":"gemini","timestamp":"${iso(NOW)}","model":"gemini-3-flash","tokens":{"input":40000,"output":1500,"cached":40000,"thoughts":1000,"tool":0}},{"id":"u","type":"user","content":"SECRET"}]}"""
        val e = Usage.geminiEntries(json).single()
        assertEquals(listOf(0.0, 2500.0, 40000.0), listOf(e.input, e.output, e.cacheRead))
        val jsonl = """{"sessionId":"x"}
{"id":"m","type":"gemini","timestamp":"${iso(NOW)}","model":"gemini-2.5-flash","tokens":{"input":10000,"output":800,"cached":2000,"thoughts":200,"tool":100}}
{"id":"m","type":"gemini","timestamp":"${iso(NOW)}","model":"gemini-2.5-flash","tokens":{"input":10000,"output":900,"cached":2000,"thoughts":200,"tool":100}}
{"broken"""
        val g = Usage.geminiEntries(jsonl).single()
        assertEquals(listOf(8100.0, 1100.0, 2000.0), listOf(g.input, g.output, g.cacheRead))
    }

    @Test fun pricesAndFormats() {
        val t = Prices.buildTable()
        assertEquals("claude-opus-4-5", Prices.priceFor(t, "claude-opus-4-5-20251101")!!.first)
        assertEquals("claude-opus-4", Prices.priceFor(t, "claude-opus-4-1-20250805")!!.first)
        assertEquals("gpt-5.1-codex", Prices.priceFor(t, "gpt-5.1-codex-max")!!.first)
        assertEquals("claude-haiku-5-5", Prices.priceFor(t, "anthropic/claude-haiku-5-5")!!.first)
        assertNull("a prefix must end at a separator", Prices.priceFor(t, "claude-opus-55"))
        val u = Prices.buildTable(mapOf("Claude-Opus-5-5" to mapOf("input" to 1.0, "output" to 2.0), "empty" to emptyMap()))
        assertEquals(1.0, u.rows.getValue("claude-opus-5-5").input, 0.0)
        assertEquals(listOf("empty"), u.ignored)
        assertEquals("0", Format.tokens(0.0)); assertEquals("950", Format.tokens(950.0)); assertEquals("1.5k", Format.tokens(1500.0))
        assertEquals("46k", Format.tokens(45600.0)); assertEquals("1.23M", Format.tokens(1234567.0)); assertEquals("25M", Format.tokens(25e6))
        assertEquals("10M", Format.tokens(1e7)); assertEquals("3.2B", Format.tokens(3.2e9))
        assertEquals("<\$0.01", Format.money(0.004)); assertEquals("\$12.35", Format.money(12.345)); assertEquals("\$1,235", Format.money(1234.5))
        assertEquals("59m", Format.duration(59 * 60000L)); assertEquals("2h 5m", Format.duration(125 * 60000L)); assertEquals("2d 2h", Format.duration(50 * 3600000L))
        assertEquals("5h", Format.windowName(300.0)); assertEquals("week", Format.windowName(10080.0)); assertEquals("limit", Format.windowName(null))
    }

    @Test fun parseLineSkipsJunk() {
        assertNull(Lines.parseLine("not json"))
        assertNull(Lines.parseLine("""{"type":"assistant","message":{"usage":{"input_tokens":12"""))
        assertNull(Lines.parseLine("[1,2]"))
        assertNotNull(Lines.parseLine("""{"a":1}"""))
    }
}
