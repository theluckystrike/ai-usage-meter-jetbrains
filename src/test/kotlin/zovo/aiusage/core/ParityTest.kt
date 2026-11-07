package zovo.aiusage.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import zovo.aiusage.core.TestSupport.NOW
import zovo.aiusage.core.TestSupport.UTC

/**
 * The JS core of the VS Code meter was run on the same fixture files (scripts/parity.js in the lane output);
 * its numbers are in parity/expected.json. The Kotlin port must give the same numbers.
 */
class ParityTest {
    private val exp: JsonObject = JsonParser.parseString(Files.readString(TestSupport.expected)).asJsonObject

    private fun summary(): Triple<Aggregate.Summary, UsageStore, List<Roots.FileInfo>> {
        val fx = TestSupport.fixtures
        val roots = TestSupport.roots(fx)
        val list = Roots.listFiles(roots, Roots.Tools(), 0)
        val store = UsageStore(historyDays = 90)
        store.update(list, NOW, Roots.geminiProjectNames(roots.gemini))
        val limits = ClaudeLimits.Reader("/nonexistent-home").read(roots.claudeConfigs)
        val s = Aggregate.summarize(store.entries(), Aggregate.Options(NOW, Prices.buildTable(), 30, Roots.Tools(), store.limits, limits, UTC))
        return Triple(s, store, list)
    }

    private fun near(msg: String, want: Double, got: Double) = assertEquals(msg, want, got, 1e-9)

    private fun checkTotals(name: String, want: JsonObject, got: Aggregate.Totals) {
        for (f in listOf("input", "output", "cacheWrite", "cacheRead", "total", "unpriced")) {
            val g = when (f) { "input" -> got.input; "output" -> got.output; "cacheWrite" -> got.cacheWrite; "cacheRead" -> got.cacheRead; "total" -> got.total; else -> got.unpriced }
            near("$name.$f", want.get(f).asDouble, g)
        }
        near("$name.cost", want.get("cost").asDouble, got.cost)
        assertEquals("$name.count", want.get("count").asInt, got.count)
    }

    @Test fun sameFilesAndEntries() {
        val (_, store, list) = summary()
        assertEquals(exp.get("files").asInt, list.size)
        assertEquals(exp.get("entries").asInt, store.entries().size)
        assertEquals(exp.get("badLines").asInt, store.stats.badLines)
    }

    @Test fun sameTodayAndRange() {
        val (s, _, _) = summary()
        val today = exp.getAsJsonObject("today")
        for (t in Aggregate.TOOLS) checkTotals("today.$t", today.getAsJsonObject(t), s.today.getValue(t))
        checkTotals("range", exp.getAsJsonObject("range"), s.range)
    }

    @Test fun samePerDayPerModelPerAccount() {
        val (s, _, _) = summary()
        val days = exp.getAsJsonArray("perDay")
        assertEquals(days.size(), s.perDay.size)
        for (i in 0 until days.size()) {
            val w = days[i].asJsonObject
            val d = s.perDay[i]
            assertEquals(w.get("day").asString, d.day)
            near("day ${d.day} total", w.get("total").asDouble, d.all.total)
            near("day ${d.day} cost", w.get("cost").asDouble, d.all.cost)
            for (t in Aggregate.TOOLS) near("day ${d.day} $t", w.get(t).asDouble, d.byTool.getValue(t).total)
        }
        val models = exp.getAsJsonArray("perModel")
        assertEquals(models.size(), s.perModel.size)
        for (i in 0 until models.size()) {
            val w = models[i].asJsonObject
            assertEquals(w.get("name").asString, s.perModel[i].name)
            assertEquals(w.get("tool").asString, s.perModel[i].tool)
            near("model total", w.get("total").asDouble, s.perModel[i].total)
            near("model cost", w.get("cost").asDouble, s.perModel[i].cost)
        }
        val accts = exp.getAsJsonArray("perAccount")
        assertEquals("two Claude accounts", 2, s.perAccount.size)
        for (i in 0 until accts.size()) {
            val w = accts[i].asJsonObject
            assertEquals(w.get("name").asString, Paths.get(s.perAccount[i].name).fileName.toString())
            near("account total", w.get("total").asDouble, s.perAccount[i].total)
        }
        val projects = exp.getAsJsonArray("perProject")
        assertEquals(projects.map { it.asJsonObject.get("name").asString }, s.perProject.map { it.name })
        assertEquals(exp.getAsJsonArray("unpricedModels").map { it.asString }, s.unpricedModels)
    }

    @Test fun sameBlockAndLimits() {
        val (s, _, _) = summary()
        val b = exp.getAsJsonObject("block")
        val block = s.block!!
        assertEquals(b.get("start").asLong, block.start)
        assertEquals(b.get("end").asLong, block.end)
        near("block total", b.get("total").asDouble, block.totals.total)
        assertEquals(b.get("perMinute").asLong, block.perMinute)
        val cl = exp.getAsJsonObject("codexLimits")
        val l = s.limits!!
        assertEquals(cl.get("ts").asLong, l.ts)
        assertEquals(cl.get("plan").asString, l.plan)
        assertEquals(42.5, l.primary!!.usedPercent, 0.0)
        assertEquals(cl.getAsJsonObject("primary").get("resetsAt").asLong, l.primary!!.resetsAt)
        assertEquals(cl.getAsJsonObject("secondary").get("resetsAt").asLong, l.secondary!!.resetsAt)
        val accts = exp.getAsJsonArray("claudeLimits")
        assertEquals(accts.size(), s.claudeLimits.size)
        for (i in 0 until accts.size()) {
            val w = accts[i].asJsonObject
            val a = s.claudeLimits[i]
            assertEquals(w.get("account").asString, Paths.get(a.account).fileName.toString())
            assertEquals(w.get("fetchedAt").asLong, a.fetchedAt)
            val ws = w.getAsJsonArray("windows")
            assertEquals(ws.map { it.asJsonObject.get("key").asString }, a.windows.map { it.key })
            assertEquals(ws.map { it.asJsonObject.get("usedPercent").asDouble }, a.windows.map { it.usedPercent })
        }
    }

    @Test fun sameStatusBarText() {
        val (s, _, _) = summary()
        val js = exp.get("statusText").asString.removePrefix("\$(pulse) ")
        val v = StatusView.build(s, StatusView.Options(showTokens = true, zone = UTC))
        assertEquals(js, v.text)
        val jsNoCost = exp.get("statusTextNoCost").asString.removePrefix("\$(pulse) ")
        assertEquals(jsNoCost, StatusView.build(s, StatusView.Options(showTokens = true, showCost = false, zone = UTC)).text)
        val short = StatusView.build(s, StatusView.Options(zone = UTC))
        assertEquals("Claude 5h 34% wk 61% · Codex 5h 43% wk 71% · Gemini 64k \$0.02", short.text)
        assertFalse(short.warn)
        assertTrue(short.tooltipHtml().contains("Claude Code: 475k tokens, \$1.19 est."))
    }

    @Test fun noPrivateTextLeaks() {
        val (s, store, _) = summary()
        val blob = StringBuilder()
        for (e in store.entries()) blob.append(e.key).append(e.model).append(e.project).append(e.account).append(e.file)
        blob.append(StatusView.build(s, StatusView.Options(zone = UTC)).tooltipHtml())
        for (a in s.claudeLimits) blob.append(a.toString())
        blob.append(s.limits.toString())
        assertFalse("marker text leaked", blob.contains(TestSupport.MARK))
    }
}
