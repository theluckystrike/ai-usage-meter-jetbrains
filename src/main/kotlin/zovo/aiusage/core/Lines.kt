package zovo.aiusage.core

import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Reads the complete new lines of a file from a byte offset (port of lines.js). Never loads the whole file,
 * never writes. A last line without a newline is left for the next read, so a line the tool is still writing
 * is not parsed half-done. Lines longer than maxLineBytes are skipped (and counted) instead of buffered.
 */
object Lines {
    data class Result(val offset: Long, val lines: Int, val longLines: Int, val cancelled: Boolean)

    fun readNewLines(
        file: Path,
        start: Long,
        end: Long? = null,
        chunkSize: Int = 512 * 1024,
        maxLineBytes: Int = 32 * 1024 * 1024,
        isCancelled: () -> Boolean = { false },
        onLine: (String) -> Unit,
    ): Result {
        val from = maxOf(0L, start)
        if (end != null && end > 0 && end <= from) return Result(from, 0, 0, false)
        var lines = 0
        var longLines = 0
        var cancelled = false
        var lineEnd = from
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val limit = if (end != null && end > 0) end else Long.MAX_VALUE
            val buf = ByteBuffer.allocate(chunkSize)
            val part = ByteArrayOutputStream()
            var skipping = false
            var pos = from
            ch.position(from)
            while (pos < limit) {
                if (isCancelled()) { cancelled = true; break }
                buf.clear()
                val want = minOf(chunkSize.toLong(), limit - pos).toInt()
                buf.limit(want)
                val n = ch.read(buf)
                if (n <= 0) break
                val a = buf.array()
                var s = 0
                var i = 0
                while (i < n) {
                    if (a[i] == NL) {
                        val bytes: ByteArray
                        if (part.size() > 0) { part.write(a, s, i - s); bytes = part.toByteArray(); part.reset() }
                        else bytes = a.copyOfRange(s, i)
                        lineEnd = pos + i + 1
                        s = i + 1
                        if (skipping) { skipping = false; i++; continue }
                        var line = String(bytes, Charsets.UTF_8)
                        if (line.endsWith("\r")) line = line.dropLast(1)
                        if (line.isNotEmpty()) {
                            lines++
                            try { onLine(line) } catch (_: Exception) { /* a bad line never stops the read */ }
                        }
                    }
                    i++
                }
                if (s < n && !skipping) {
                    part.write(a, s, n - s)
                    if (part.size() > maxLineBytes) { part.reset(); skipping = true; longLines++ }
                }
                pos += n
            }
        }
        return Result(lineEnd, lines, longLines, cancelled)
    }

    private const val NL: Byte = 10

    /** Parses one JSONL line into an object, or null for a bad line. */
    fun parseLine(s: String): JsonObject? {
        if (s.isEmpty()) return null
        val c = s[0]
        if (c != '{' && c != ' ' && c != '\t') return null
        return parseJson(s) as? JsonObject
    }
}
