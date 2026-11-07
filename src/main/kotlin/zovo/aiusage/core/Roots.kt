package zovo.aiusage.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** Finds usage files (port of roots.js). Read-only: it lists folders and reads file sizes and times. */
object Roots {
    /** Folder settings. Empty lists mean "use the defaults". */
    data class Settings(
        val claudeRoots: List<String> = emptyList(),
        val extraClaudeConfigs: List<String> = emptyList(),
        val codexRoots: List<String> = emptyList(),
        val geminiRoots: List<String> = emptyList(),
        val findClaudeAccounts: Boolean = true,
    )

    data class ClaudeConfig(val dir: String, val label: String)

    data class Resolved(
        val claude: List<String>,
        val codex: List<String>,
        val gemini: List<String>,
        val claudeConfigs: List<ClaudeConfig>,
        val claudeAccount: Map<String, String>,
    )

    /** A usage file. tool is "claude", "codex" or "gemini". */
    data class FileInfo(
        val file: String,
        val tool: String,
        val projectDir: String = "",
        val account: String = "",
        val size: Long,
        val mtimeMs: Long,
        val ino: Any?,
    )

    private val sep = File.separator

    private fun join(vararg parts: String): String = Paths.get(parts[0], *parts.drop(1).toTypedArray()).toString()

    fun expandHome(p: String?, home: String): String {
        if (p == null || p.isBlank()) return ""
        val t = p.trim()
        if (t == "~") return home
        if (t.startsWith("~/") || t.startsWith("~\\")) return join(home, t.substring(2))
        return t
    }

    private fun isDir(p: String) = try { Files.isDirectory(Paths.get(p)) } catch (_: Exception) { false }

    private val CLAUDE_DIR = Regex("^\\.claude[-_.][\\w.-]{1,60}$")
    private val SKIP_EXT = Regex("\\.(json|bak|old|lock|backup)$", RegexOption.IGNORE_CASE)

    /**
     * Claude Code config folders in the home folder, for people who run several accounts with CLAUDE_CONFIG_DIR
     * (for example ~/.claude-work). Only folders that hold a projects folder count.
     */
    fun discoverClaudeDirs(home: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(d: String) { if (isDir(join(d, "projects"))) out.add(d) }
        add(join(home, ".claude"))
        add(join(home, ".config", "claude"))
        val names = try { File(home).list()?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        for (n in names.sorted()) {
            if (CLAUDE_DIR.matches(n) && !SKIP_EXT.containsMatchIn(n) && !n.contains(".json.")) add(join(home, n))
        }
        return out.toList()
    }

    /** Short label for a Claude Code config folder: "~/.claude", "~/.claude-work". */
    fun accountLabel(dir: String, home: String): String {
        if (home.isNotEmpty() && (dir == home || dir.startsWith(home + sep))) return "~" + dir.substring(home.length)
        return dir
    }

    private fun baseName(p: String) = Paths.get(p).fileName?.toString() ?: p
    private fun parent(p: String) = Paths.get(p).parent?.toString() ?: p

    /**
     * Folder roots for each tool. Settings win; then CLAUDE_CONFIG_DIR / CODEX_HOME; then ~/.claude, ~/.codex
     * and ~/.gemini/tmp. With findClaudeAccounts on and no setting or env, other ~/.claude-* config folders are
     * read too. Extra config folders from the settings are always added.
     */
    fun resolve(settings: Settings, env: Map<String, String>, home: String): Resolved {
        fun clean(a: List<String>) = a.map { expandHome(it, home) }.filter { it.isNotEmpty() }
        var claude = clean(settings.claudeRoots)
        var codex = clean(settings.codexRoots)
        var gemini = clean(settings.geminiRoots)
        var configs: List<String>
        if (claude.isNotEmpty()) {
            configs = claude.map { if (baseName(it) == "projects") parent(it) else it }
        } else {
            val ccd = env["CLAUDE_CONFIG_DIR"]
            if (!ccd.isNullOrEmpty()) configs = ccd.split(',').map { expandHome(it, home) }.filter { it.isNotEmpty() }
            else {
                configs = if (!settings.findClaudeAccounts) emptyList() else discoverClaudeDirs(home)
                if (configs.isEmpty()) configs = listOf(join(home, ".claude"))
            }
            claude = configs.map { join(it, "projects") }
        }
        val extra = clean(settings.extraClaudeConfigs).map { if (baseName(it) == "projects") parent(it) else it }
        for (d in extra) if (d !in configs) { configs = configs + d; claude = claude + join(d, "projects") }
        if (codex.isEmpty()) {
            val ch = env["CODEX_HOME"]
            codex = listOf(join(if (!ch.isNullOrEmpty()) expandHome(ch, home) else join(home, ".codex"), "sessions"))
        }
        if (gemini.isEmpty()) gemini = listOf(join(home, ".gemini", "tmp"))
        val claudeAccount = LinkedHashMap<String, String>()
        claude.forEachIndexed { i, r -> if (r !in claudeAccount) claudeAccount[r] = accountLabel(configs.getOrNull(i) ?: r, home) }
        val claudeConfigs = configs.distinct().map { ClaudeConfig(it, accountLabel(it, home)) }
        return Resolved(claude.distinct(), codex.distinct(), gemini.distinct(), claudeConfigs, claudeAccount)
    }

    private fun children(dir: String): List<Path> = try {
        Files.newDirectoryStream(Paths.get(dir)).use { s -> s.toList() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun stat(p: Path): BasicFileAttributes? = try {
        Files.readAttributes(p, BasicFileAttributes::class.java)
    } catch (_: Exception) {
        null
    }

    private fun isDirNoFollow(p: Path) = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
    private fun isFileNoFollow(p: Path) = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)

    // Claude Code: <root>/<encoded-cwd>/<session>.jsonl, plus subagent files below a session folder.
    private fun walkClaude(dir: String, projectDir: String, depth: Int, out: MutableList<FileInfo>, since: Long, account: String) {
        if (depth > 4) return
        for (p in children(dir)) {
            val name = p.fileName.toString()
            if (isDirNoFollow(p)) walkClaude(p.toString(), projectDir.ifEmpty { name }, depth + 1, out, since, account)
            else if (isFileNoFollow(p) && projectDir.isNotEmpty() && name.endsWith(".jsonl")) {
                val st = stat(p) ?: continue
                val mt = st.lastModifiedTime().toMillis()
                if (st.size() > 0 && mt >= since) out.add(FileInfo(p.toString(), "claude", projectDir, account, st.size(), mt, st.fileKey()))
            }
        }
    }

    private val GEMINI_FILE = Regex("^session-.*\\.jsonl?$")
    private val CODEX_FILE = Regex("^rollout-.*\\.jsonl$")

    // Gemini CLI: <root>/<project>/chats/session-*.json (older and current) or session-*.jsonl.
    private fun walkGemini(dir: String, out: MutableList<FileInfo>, since: Long) {
        for (p in children(dir)) {
            if (!isDirNoFollow(p)) continue
            for (f in children(p.resolve("chats").toString())) {
                if (!isFileNoFollow(f) || !GEMINI_FILE.matches(f.fileName.toString())) continue
                val st = stat(f) ?: continue
                val mt = st.lastModifiedTime().toMillis()
                if (st.size() > 0 && mt >= since) out.add(FileInfo(f.toString(), "gemini", p.fileName.toString(), "", st.size(), mt, st.fileKey()))
            }
        }
    }

    private fun walkCodex(dir: String, depth: Int, out: MutableList<FileInfo>, since: Long) {
        if (depth > 5) return
        for (p in children(dir)) {
            if (isDirNoFollow(p)) walkCodex(p.toString(), depth + 1, out, since)
            else if (isFileNoFollow(p) && CODEX_FILE.matches(p.fileName.toString())) {
                val st = stat(p) ?: continue
                val mt = st.lastModifiedTime().toMillis()
                if (st.size() > 0 && mt >= since) out.add(FileInfo(p.toString(), "codex", "", "", st.size(), mt, st.fileKey()))
            }
        }
    }

    data class Tools(val claude: Boolean = true, val codex: Boolean = true, val gemini: Boolean = true)

    /** Lists usage files changed since [since] (ms). */
    fun listFiles(roots: Resolved, tools: Tools = Tools(), since: Long = 0): List<FileInfo> {
        val out = ArrayList<FileInfo>()
        if (tools.claude) for (r in roots.claude) walkClaude(r, "", 0, out, since, roots.claudeAccount[r] ?: "")
        if (tools.codex) for (r in roots.codex) walkCodex(r, 0, out, since)
        if (tools.gemini) for (r in roots.gemini) walkGemini(r, out, since)
        val seen = HashSet<String>()
        return out.filter { seen.add(it.file) }
    }

    /**
     * Gemini CLI keeps a map of project paths to folder names in <root>/../projects.json. Returns
     * { folderName: path }. A missing or bad file gives an empty map.
     */
    fun geminiProjectNames(roots: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (r in roots) {
            try {
                val f = Paths.get(r).resolve("..").resolve("projects.json").normalize()
                if (!Files.isRegularFile(f) || Files.size(f) > 8L * 1024 * 1024) continue
                val m = parseJson(Files.readString(f)).asObj()?.obj("projects") ?: continue
                for ((p, v) in m.entrySet()) {
                    val name = (v as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString ?: continue
                    if (name.isNotEmpty() && name !in out) out[name] = p
                }
            } catch (_: Exception) { /* no map: folder names are used */ }
        }
        return out
    }

    /** Turns a Claude project folder name ("-Users-me-repo") into a readable fallback label. */
    fun projectLabelFromDir(name: String?): String = if (name.isNullOrEmpty()) "" else name.replaceFirst(Regex("^-"), "/").replace('-', '/')
}
