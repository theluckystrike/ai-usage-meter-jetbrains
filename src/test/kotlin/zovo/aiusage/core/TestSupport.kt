package zovo.aiusage.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId

object TestSupport {
    /** Fixed clock for every test: 2026-11-06 18:00 UTC, the time the fixtures were made for. */
    const val NOW = 1793988000000L
    val UTC: ZoneId = ZoneId.of("UTC")
    const val MARK = "PRIVATE-TEXT-MARKER"

    val fixtures: Path get() = Paths.get(System.getProperty("aiusage.fixtures") ?: "src/test/resources/fixtures")
    val expected: Path get() = Paths.get(System.getProperty("aiusage.expected") ?: "src/test/resources/parity/expected.json")

    /** A fresh folder under build/test-tmp (not /tmp). Delete it in the test teardown. */
    fun tempDir(prefix: String): Path {
        val root = Paths.get(System.getProperty("aiusage.testRoot") ?: "build/test-tmp")
        Files.createDirectories(root)
        return Files.createTempDirectory(root, prefix)
    }

    fun deleteTree(p: Path) {
        if (!Files.exists(p)) return
        Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** Copies the fixtures into a temp folder, for tests that change files. */
    fun copyFixtures(): Path {
        val dir = tempDir("fx-")
        val src = fixtures
        Files.walk(src).use { s ->
            s.forEach { p ->
                val t = dir.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(t) else Files.copy(p, t)
            }
        }
        return dir
    }

    fun roots(fx: Path): Roots.Resolved = Roots.resolve(
        Roots.Settings(geminiRoots = listOf(fx.resolve("gemini/tmp").toString())),
        mapOf("CLAUDE_CONFIG_DIR" to "${fx.resolve("claude")},${fx.resolve("claude-work")}", "CODEX_HOME" to fx.resolve("codex").toString()),
        "/nonexistent-home",
    )
}
