package zovo.aiusage.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import zovo.aiusage.core.TestSupport.NOW
import zovo.aiusage.core.TestSupport.UTC
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class StoreTest {
    private val cleanup = ArrayList<Path>()

    @After fun teardown() {
        for (p in cleanup) TestSupport.deleteTree(p)
    }

    private fun fx(): Path = TestSupport.copyFixtures().also { cleanup.add(it) }

    private fun load(dir: Path, store: UsageStore): Pair<Roots.Resolved, List<Roots.FileInfo>> {
        val roots = TestSupport.roots(dir)
        val list = Roots.listFiles(roots)
        store.update(list, NOW, Roots.geminiProjectNames(roots.gemini))
        return roots to list
    }

    @Test fun twoClaudeAccountsAreSplit() {
        val dir = fx()
        val store = UsageStore()
        val (roots, _) = load(dir, store)
        assertEquals(2, roots.claudeConfigs.size)
        val limits = ClaudeLimits.Reader("/nonexistent-home").read(roots.claudeConfigs)
        assertEquals(listOf("claude", "claude-work"), limits.map { it.account.substringAfterLast('/') })
        val s = Aggregate.summarize(store.entries(), Aggregate.Options(NOW, Prices.buildTable(), 30, Roots.Tools(), store.limits, limits, UTC))
        val work = s.perAccount.single { it.name.endsWith("claude-work") }
        assertEquals("4 messages of 800+1500+4000+30000", 4 * 36300.0, work.total, 0.0)
        // the work account's 5-hour window reset 30 minutes ago: no stale percent; its weekly window is still open
        val v = StatusView.build(s, StatusView.Options(zone = UTC))
        assertTrue(v.tooltipLines.any { it.endsWith("claude-work: 5-hour: window has reset since Claude Code saved it") })
        assertTrue(v.tooltipLines.any { it.contains("claude-work: weekly: 18% used") })
        assertTrue(v.text.startsWith("Claude 5h 34% wk 61%"))
    }

    @Test fun incrementalReadWaitsForAFullLastLine() {
        val dir = fx()
        val store = UsageStore()
        val (roots, list) = load(dir, store)
        val target = list.first { it.file.contains("11111111") }
        var r = store.update(Roots.listFiles(roots), NOW)
        assertEquals("nothing changed, nothing read", 0L, r.bytesRead)
        assertFalse(r.changed)
        val line = """{"type":"assistant","timestamp":"${Instant.ofEpochMilli(NOW - 60000)}","cwd":"/Users/demo/shop-api","message":{"id":"msg_new","model":"claude-opus-5-5","usage":{"input_tokens":7,"output_tokens":8,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        val half = line.length / 2
        val p = Path.of(target.file)
        Files.writeString(p, line.substring(0, half), StandardOpenOption.APPEND)
        r = store.update(Roots.listFiles(roots), NOW)
        assertEquals("a half-written (truncated) last line is not consumed", 0L, r.bytesRead)
        assertFalse(store.hasClaudeKey("msg_new:"))
        Files.writeString(p, line.substring(half) + "\n", StandardOpenOption.APPEND)
        r = store.update(Roots.listFiles(roots), NOW)
        assertEquals(line.toByteArray().size + 1L, r.bytesRead)
        assertTrue(store.hasClaudeKey("msg_new:"))
    }

    @Test fun truncatedOrDeletedFileIsForgottenAndReRead() {
        val dir = fx()
        val store = UsageStore()
        val (roots, list) = load(dir, store)
        val target = list.first { it.file.contains("11111111") }
        val p = Path.of(target.file)
        fun has() = store.entries().any { it.file == target.file }
        assertTrue(has())
        Files.writeString(p, "")
        store.update(Roots.listFiles(roots), NOW)
        assertFalse(has())
        Files.writeString(p, """{"type":"assistant","timestamp":"${Instant.ofEpochMilli(NOW)}","message":{"id":"z","usage":{"input_tokens":1}}}""" + "\n")
        store.update(Roots.listFiles(roots), NOW)
        assertTrue(store.hasClaudeKey("z:"))
        Files.delete(p)
        store.update(Roots.listFiles(roots), NOW)
        assertFalse(store.hasClaudeKey("z:"))
    }

    @Test fun historyCutoffDropsOldEntries() {
        val dir = fx()
        val store = UsageStore(historyDays = 7)
        load(dir, store)
        for (e in store.entries()) assertTrue(e.ts >= NOW - 7 * UsageStore.DAY_MS)
        val future = Roots.listFiles(TestSupport.roots(dir), Roots.Tools(), System.currentTimeMillis() + 3600000)
        assertEquals(0, future.size)
    }

    @Test fun fiftyMegabyteFileIsReadIncrementally() {
        val dir = TestSupport.tempDir("big-").also { cleanup.add(it) }
        val f = dir.resolve("big.jsonl")
        val line = """{"type":"assistant","timestamp":"${Instant.ofEpochMilli(NOW)}","message":{"id":"x","usage":{"input_tokens":1}},"pad":"${"p".repeat(900)}"}""" + "\n"
        val block = line.repeat(1000).toByteArray()
        Files.newOutputStream(f).use { out ->
            repeat(46) { out.write(block) } // about 45 MB, under the 50 MB fixture limit
            out.write(("x".repeat(3 * 1024 * 1024) + "\n").toByteArray())
            out.write(line.toByteArray())
            out.write(line.substring(0, 100).toByteArray()) // a truncated last line
        }
        val size = Files.size(f)
        assertTrue("about 50 MB", size in 45_000_000L..50_000_000L)
        var n = 0
        val first = Lines.readNewLines(f, 0, end = size / 2, chunkSize = 64 * 1024, maxLineBytes = 1024 * 1024) { n++ }
        assertTrue(first.offset <= size / 2)
        val second = Lines.readNewLines(f, first.offset, end = size, chunkSize = 64 * 1024, maxLineBytes = 1024 * 1024) { n++ }
        assertEquals(46001, n)
        assertEquals(1, first.longLines + second.longLines)
        assertEquals("the truncated last line waits for the next read", size - 100, second.offset)
        val third = Lines.readNewLines(f, second.offset, end = size) { n++ }
        assertEquals(46001, n)
        assertEquals(second.offset, third.offset)
    }
}
