package zovo.aiusage.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Short number, money and time formats for the status bar and its tooltip (port of format.js). */
object Format {
    /** JavaScript's Number.prototype.toFixed for positive values. */
    internal fun toFixed(n: Double, digits: Int): String = BigDecimal(n).setScale(digits, RoundingMode.HALF_UP).toPlainString()

    private val trimZeros = Regex("\\.?0+$")

    fun tokens(n: Double): String {
        if (!n.isFinite() || n <= 0) return "0"
        if (n < 1000) return Math.round(n).toString()
        if (n < 1e6) return toFixed(n / 1e3, if (n < 1e4) 1 else 0).replaceFirst(Regex("\\.0$"), "") + "k"
        if (n < 1e9) return toFixed(n / 1e6, if (n < 1e7) 2 else 1).replaceFirst(trimZeros, "") + "M"
        return toFixed(n / 1e9, 2).replaceFirst(trimZeros, "") + "B"
    }

    fun money(n: Double): String {
        if (!n.isFinite() || n <= 0) return "$0"
        if (n < 0.01) return "<$0.01"
        if (n < 100) return "$" + toFixed(n, 2)
        return "$" + NumberFormat.getIntegerInstance(Locale.US).format(Math.round(n))
    }

    fun duration(ms: Long): String {
        if (ms <= 0) return "0m"
        val m = Math.ceil(ms / 60000.0).toLong()
        val h = m / 60
        val d = h / 24
        if (d >= 1) return "${d}d ${h % 24}h"
        return if (h > 0) "${h}h ${m % 60}m" else "${m}m"
    }

    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
    }

    fun windowName(minutes: Double?): String {
        if (minutes == null || minutes == 0.0) return "limit"
        if (minutes == 300.0) return "5h"
        if (minutes == 10080.0) return "week"
        val m = minutes.toLong()
        if (minutes == m.toDouble()) {
            if (m % 1440 == 0L) return "${m / 1440}d"
            if (m % 60 == 0L) return "${m / 60}h"
        }
        return "${trimNumber(minutes)}m"
    }

    internal fun trimNumber(d: Double): String = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** A Codex limit window for display; usedPercent is null when the window has reset since Codex wrote it. */
    fun limitView(w: LimitWindow?, now: Long): WindowView? {
        if (w == null) return null
        val reset = w.resetsAt != null && w.resetsAt <= now
        val name = windowName(w.windowMinutes)
        return WindowView(
            key = name, name = name, label = name,
            usedPercent = if (reset) null else jsRound(w.usedPercent),
            resetsAt = w.resetsAt,
            resetsIn = w.resetsAt?.let { maxOf(0L, it - now) },
            reset = reset,
        )
    }

    /** JavaScript Math.round: halves round up. */
    fun jsRound(d: Double): Long = Math.floor(d + 0.5).toLong()
}

/** One limit window as shown: Claude Code plan limits and Codex rate limits share it. */
data class WindowView(
    val key: String,
    val name: String,
    val label: String,
    val usedPercent: Long?,
    val resetsAt: Long?,
    val resetsIn: Long?,
    val reset: Boolean,
)
