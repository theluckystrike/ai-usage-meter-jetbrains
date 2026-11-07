package zovo.aiusage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Limit warnings: one id per window, also across IDE restarts and across Codex events in the same window. */
class AlertTest {
    private val now = 1_800_000_000_000L
    private val table = Prices.buildTable()

    private fun codexAlerts(resetsAt: Long, used: Double = 95.0): List<StatusView.Alert> {
        val limits = CodexLimits(now - 1000, LimitWindow(used, resetsAt, 300.0), null, null, null, "plus")
        val s = Aggregate.summarize(emptyList(), Aggregate.Options(now, table, limits = limits, zone = ZoneOffset.UTC))
        return StatusView.build(s, StatusView.Options(zone = ZoneOffset.UTC)).alerts
    }

    @Test fun sameWindowGivesSameIdDespiteMillisecondJitter() {
        val a = codexAlerts(now + 3_600_000L + 123)
        val b = codexAlerts(now + 3_600_000L + 456)
        assertEquals(1, a.size)
        assertEquals(a[0].id, b[0].id)
    }

    @Test fun nextWindowGivesNewId() {
        assertTrue(codexAlerts(now + 3_600_000L)[0].id != codexAlerts(now + 3_600_000L + 5 * 3_600_000L)[0].id)
    }

    @Test fun belowWarnLevelGivesNoAlert() {
        assertEquals(0, codexAlerts(now + 3_600_000L, used = 50.0).size)
    }

    @Test fun shownIdsRoundTripAndKeepNewest() {
        val ids = (1..250).map { "codex|5h|$it" }
        val text = StatusView.formatShown(ids)
        val back = StatusView.parseShown(text)
        assertEquals(200, back.size)
        assertEquals("codex|5h|51", back.first())
        assertEquals("codex|5h|250", back.last())
        assertEquals(0, StatusView.parseShown(null).size)
        assertEquals(0, StatusView.parseShown("\n \n").size)
    }

    @Test fun alertMinuteRounds() {
        assertEquals(0L, StatusView.alertMinute(null))
        assertEquals(StatusView.alertMinute(60_000L * 100 + 29_000), StatusView.alertMinute(60_000L * 100 - 29_000))
    }
}
