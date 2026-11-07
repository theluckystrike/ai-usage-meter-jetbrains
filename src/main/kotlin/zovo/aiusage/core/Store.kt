package zovo.aiusage.core

import java.nio.file.Files
import java.nio.file.Paths

/**
 * Keeps usage entries in memory and reads only what was added to each file since the last read (port of
 * store.js). Per file it remembers the byte offset after the last complete line, the file key and the Codex
 * running totals. Not thread-safe: one caller at a time (the service refreshes on one background thread).
 */
class UsageStore(
    val historyDays: Int = 90,
    private val maxLineBytes: Int = 32 * 1024 * 1024,
    private val maxWholeFileBytes: Long = 128L * 1024 * 1024,
) {
    companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }

    private class FileState(val tool: String, val ino: Any?, val projectDir: String, val account: String) {
        var offset = 0L
        val codex = CodexState()
        val keys = HashSet<String>()
        var entries: MutableList<Entry> = ArrayList()
        var line = 0
        var mtimeMs = 0L
    }

    class Stats {
        var badLines = 0; var longLines = 0; var bytesRead = 0L; var filesRead = 0; var scans = 0; var bigFiles = 0
    }

    data class UpdateResult(val changed: Boolean, val filesRead: Int, val bytesRead: Long)

    private var geminiNames: Map<String, String> = emptyMap()
    private val files = LinkedHashMap<String, FileState>()
    private val claude = LinkedHashMap<String, Entry>()
    /** Newest Codex rate limits. */
    var limits: CodexLimits? = null
        private set
    val stats = Stats()
    val fileCount get() = files.size

    fun cutoff(now: Long) = now - historyDays * DAY_MS

    fun hasClaudeKey(k: String) = claude.containsKey(k)

    private fun forget(file: String) {
        val st = files[file] ?: return
        for (k in st.keys) {
            val e = claude[k]
            if (e != null && e.file == file) claude.remove(k)
        }
        files.remove(file)
    }

    /** Reads new lines from the listed files. Files that left the listing are forgotten. */
    fun update(
        listing: List<Roots.FileInfo>,
        now: Long = System.currentTimeMillis(),
        geminiNames: Map<String, String>? = null,
        isCancelled: () -> Boolean = { false },
    ): UpdateResult {
        val cutoff = cutoff(now)
        if (geminiNames != null) this.geminiNames = geminiNames
        var changed = false
        var filesRead = 0
        var bytesRead = 0L
        val listed = listing.map { it.file }.toHashSet()
        for (file in files.keys.toList()) if (file !in listed) { forget(file); changed = true }
        // Oldest first, so the newest Codex rate limits win.
        for (f in listing.sortedBy { it.mtimeMs }) {
            if (isCancelled()) break
            var st = files[f.file]
            if (st != null && (f.size < st.offset || (f.ino != null && st.ino != null && f.ino != st.ino))) { forget(f.file); st = null; changed = true }
            if (st == null) {
                st = FileState(f.tool, f.ino, f.projectDir, f.account)
                files[f.file] = st
            }
            if (f.tool == "gemini") {
                // Gemini CLI rewrites the whole session file, so it is read again in full when it changes.
                if (st.offset == f.size && st.mtimeMs == f.mtimeMs) continue
                if (f.size > maxWholeFileBytes) { stats.bigFiles++; st.offset = f.size; st.mtimeMs = f.mtimeMs; continue }
                val text = try { Files.readString(Paths.get(f.file)) } catch (_: Exception) { continue }
                st.entries = geminiFrom(text, st, cutoff)
                st.offset = f.size; st.mtimeMs = f.mtimeMs
                changed = true; filesRead++; bytesRead += f.size
                continue
            }
            if (f.size <= st.offset) continue
            val before = st.offset
            val state = st
            try {
                val r = Lines.readNewLines(Paths.get(f.file), st.offset, end = f.size, maxLineBytes = maxLineBytes, isCancelled = isCancelled) { ingest(f.file, state, it, cutoff) }
                st.offset = r.offset
                stats.longLines += r.longLines
            } catch (_: Exception) {
                continue // file vanished or unreadable: try again next time
            }
            if (st.offset > before) { changed = true; filesRead++; bytesRead += st.offset - before }
        }
        prune(now)
        stats.bytesRead += bytesRead
        stats.filesRead += filesRead
        stats.scans++
        return UpdateResult(changed, filesRead, bytesRead)
    }

    private fun geminiFrom(text: String, st: FileState, cutoff: Long): MutableList<Entry> {
        val project = geminiNames[st.projectDir] ?: st.projectDir
        val out = ArrayList<Entry>()
        for (e in Usage.geminiEntries(text)) {
            if (e.ts < cutoff) continue
            e.tool = "gemini"
            e.project = project
            out.add(e)
        }
        return out
    }

    private fun ingest(file: String, st: FileState, line: String, cutoff: Long) {
        st.line++
        if (st.tool == "claude") {
            if (!Usage.claudeWorth(line)) return
            val rec = Lines.parseLine(line)
            if (rec == null) { stats.badLines++; return }
            val e = Usage.claudeEntry(rec, file + "#" + st.line) ?: return
            if (e.ts < cutoff) return
            e.tool = "claude"
            e.file = file
            e.project = e.cwd.ifEmpty { Roots.projectLabelFromDir(st.projectDir) }
            e.account = st.account
            e.cwd = ""
            val old = claude[e.key]
            // The same message can be written several times while it streams; keep the most complete one.
            if (old != null && old.output + old.input + old.cacheWrite + old.cacheRead >= e.output + e.input + e.cacheWrite + e.cacheRead) return
            claude[e.key] = e
            st.keys.add(e.key)
            return
        }
        if (!Usage.codexWorth(line)) return
        val rec = Lines.parseLine(line)
        if (rec == null) { stats.badLines++; return }
        val step = Usage.codexStep(rec, st.codex)
        val l = step.limits
        if (l != null && (limits == null || l.ts >= limits!!.ts)) limits = l
        val entry = step.entry
        if (entry != null && entry.ts >= cutoff) {
            entry.tool = "codex"
            entry.project = entry.cwd
            entry.cwd = ""
            st.entries.add(entry)
        }
    }

    private fun prune(now: Long) {
        val cutoff = cutoff(now)
        claude.values.removeIf { it.ts < cutoff }
        for (st in files.values) if (st.entries.isNotEmpty() && st.entries[0].ts < cutoff) st.entries = st.entries.filter { it.ts >= cutoff }.toMutableList()
    }

    /** All entries (Claude Code, Codex and Gemini CLI). */
    fun entries(): List<Entry> {
        val out = ArrayList<Entry>(claude.size)
        out.addAll(claude.values)
        for (st in files.values) out.addAll(st.entries)
        return out
    }
}
