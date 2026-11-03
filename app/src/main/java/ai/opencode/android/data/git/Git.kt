package ai.opencode.android.data.git

import ai.opencode.android.data.remote.RemoteExec

/** One path of `git status --porcelain=v1`. */
data class GitFile(
    val path: String,
    val index: Char,
    val worktree: Char,
    val origPath: String? = null,
) {
    val untracked: Boolean get() = index == '?'
    val conflicted: Boolean get() = index == 'U' || worktree == 'U' || (index == 'A' && worktree == 'A') || (index == 'D' && worktree == 'D')
    val staged: Boolean get() = !untracked && index != ' ' && index != '!' && !conflicted
    val unstaged: Boolean get() = untracked || conflicted || (worktree != ' ' && worktree != '!')

    /** Hunk-level staging needs a plain modification on that side. */
    fun hunksOn(stagedSide: Boolean): Boolean = if (stagedSide) index == 'M' else worktree == 'M' && !conflicted
}

data class GitStatus(
    val isRepo: Boolean = true,
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val files: List<GitFile> = emptyList(),
) {
    val staged: List<GitFile> get() = files.filter { it.staged }
    val unstaged: List<GitFile> get() = files.filter { it.unstaged }
}

data class GitCommit(val hash: String, val author: String, val age: String, val subject: String)

/** A hunk kept as raw diff lines (with their `+`/`-`/` ` prefixes) so it can be re-applied. */
data class RawHunk(val header: String, val lines: List<String>)

data class RawFileDiff(val path: String, val headerLines: List<String>, val hunks: List<RawHunk>, val binary: Boolean = false) {
    /** A patch containing only [hunk], for `git apply`. */
    fun patchFor(hunk: RawHunk): String = (headerLines + hunk.header + hunk.lines).joinToString("\n") + "\n"

    val text: String get() = (headerLines + hunks.flatMap { listOf(it.header) + it.lines }).joinToString("\n")
}

/** Parsers and command builders for the git panel; everything runs through [RemoteExec]. */
object Git {

    const val NOT_A_REPO = "__OC_NOT_A_REPO__"
    private const val GIT = "git -c core.quotePath=false -c color.ui=never"

    val STATUS_SCRIPT = "git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo $NOT_A_REPO; exit 0; }\n" +
        "$GIT status --porcelain=v1 --branch --untracked-files=all"

    const val LOG_SCRIPT = "git log --no-color -n 30 --pretty=format:'%h%x09%an%x09%ar%x09%s' 2>/dev/null; true"

    fun parseStatus(output: String): GitStatus {
        if (output.lines().any { it.trim() == NOT_A_REPO }) return GitStatus(isRepo = false)
        var status = GitStatus()
        val files = mutableListOf<GitFile>()
        output.lines().forEach { line ->
            when {
                line.startsWith("## ") -> status = parseBranch(line.removePrefix("## "))
                line.length >= 4 && line[2] == ' ' -> {
                    val x = line[0]
                    val y = line[1]
                    val rest = line.substring(3)
                    val arrow = if (x == 'R' || x == 'C' || y == 'R' || y == 'C') splitRename(rest) else null
                    files += GitFile(
                        path = unquote(arrow?.second ?: rest),
                        index = x,
                        worktree = y,
                        origPath = arrow?.first?.let(::unquote),
                    )
                }
            }
        }
        return status.copy(files = files)
    }

    private fun parseBranch(header: String): GitStatus {
        val noCommits = Regex("^No commits yet on (.+)$").find(header)
        if (noCommits != null) return GitStatus(branch = noCommits.groupValues[1].trim())
        if (header.startsWith("HEAD (no branch)")) return GitStatus(branch = "HEAD")
        val main = header.substringBefore(" [")
        val branch = main.substringBefore("...")
        val upstream = main.substringAfter("...", "").ifBlank { null }
        val tracking = header.substringAfter(" [", "").substringBefore("]")
        val ahead = Regex("ahead (\\d+)").find(tracking)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val behind = Regex("behind (\\d+)").find(tracking)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return GitStatus(branch = branch, upstream = upstream, ahead = ahead, behind = behind)
    }

    /** `old -> new`, respecting quoted names that contain the arrow. */
    private fun splitRename(rest: String): Pair<String, String>? {
        if (rest.startsWith("\"")) {
            val end = closingQuote(rest)
            if (end > 0 && rest.startsWith(" -> ", end + 1)) return rest.substring(0, end + 1) to rest.substring(end + 5)
        }
        val index = rest.indexOf(" -> ")
        return if (index < 0) null else rest.substring(0, index) to rest.substring(index + 4)
    }

    private fun closingQuote(text: String): Int {
        var i = 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    /** Git's C-style quoting (`"a b\tc\303\251"`) back to the path. */
    fun unquote(path: String): String {
        if (path.length < 2 || !path.startsWith("\"") || !path.endsWith("\"")) return path
        val body = path.substring(1, path.length - 1)
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch != '\\' || i + 1 >= body.length) {
                bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
                i++
                continue
            }
            val next = body[i + 1]
            when {
                i + 3 < body.length && body.substring(i + 1, i + 4).all { it in '0'..'7' } -> {
                    bytes.write(body.substring(i + 1, i + 4).toInt(8))
                    i += 4
                }
                else -> {
                    val decoded = when (next) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        'a' -> '\u0007'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'v' -> '\u000B'
                        else -> next
                    }
                    bytes.write(decoded.toString().toByteArray(Charsets.UTF_8))
                    i += 2
                }
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    fun parseLog(output: String): List<GitCommit> = output.lines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 4) null else GitCommit(parts[0], parts[1], parts[2], parts.subList(3, parts.size).joinToString("\t"))
    }

    /** Splits `git diff` output into files and raw hunks. */
    fun splitDiff(output: String): List<RawFileDiff> {
        val files = mutableListOf<RawFileDiff>()
        var header = mutableListOf<String>()
        var hunks = mutableListOf<RawHunk>()
        var hunkHeader: String? = null
        var hunkLines = mutableListOf<String>()
        var path: String? = null
        var binary = false

        fun closeHunk() {
            hunkHeader?.let { hunks += RawHunk(it, hunkLines.toList()) }
            hunkHeader = null
            hunkLines = mutableListOf()
        }

        fun closeFile() {
            closeHunk()
            if (header.isNotEmpty() || hunks.isNotEmpty()) {
                files += RawFileDiff(path ?: "unknown", header.toList(), hunks.toList(), binary)
            }
            header = mutableListOf()
            hunks = mutableListOf()
            path = null
            binary = false
        }

        output.split('\n').forEach { raw ->
            val line = raw.removeSuffix("\r")
            when {
                line.startsWith("diff --git ") -> {
                    closeFile()
                    header += line
                    path = line.substringAfter(" b/", "").ifBlank { null }
                }
                hunkHeader == null && line.startsWith("@@") -> {
                    hunkHeader = line
                }
                hunkHeader != null && line.startsWith("@@") -> {
                    closeHunk()
                    hunkHeader = line
                }
                hunkHeader != null -> {
                    if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '+' || line[0] == '-' || line[0] == '\\')) hunkLines += line
                }
                else -> {
                    if (line.isEmpty()) return@forEach
                    header += line
                    when {
                        line.startsWith("+++ ") -> line.removePrefix("+++ ").takeIf { it != "/dev/null" }?.let { path = it.removePrefix("b/") }
                        line.startsWith("--- ") && path == null -> line.removePrefix("--- ").takeIf { it != "/dev/null" }?.let { path = it.removePrefix("a/") }
                        line.startsWith("Binary files ") -> binary = true
                    }
                }
            }
        }
        closeFile()
        return files
    }

    private fun b64(text: String): String = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun q(path: String) = RemoteExec.quote(path)

    fun diffScript(file: GitFile, staged: Boolean): String = when {
        file.untracked -> "$GIT diff --no-ext-diff --no-index -- /dev/null ${q(file.path)}; true"
        staged -> "$GIT diff --no-ext-diff --cached -U3 -- ${q(file.path)}"
        else -> "$GIT diff --no-ext-diff -U3 -- ${q(file.path)}"
    }

    /** Stage ([reverse] false, from the worktree diff), unstage ([cached] + [reverse]) or discard a hunk. */
    fun applyScript(patch: String, cached: Boolean, reverse: Boolean): String =
        "printf '%s' ${q(b64(patch))} | base64 -d | git apply --recount --whitespace=nowarn" +
            (if (cached) " --cached" else "") + (if (reverse) " -R" else "") + " -"

    fun stageScript(paths: List<String>): String = "git add -A -- " + paths.joinToString(" ") { q(it) }

    /** `restore --staged` is git ≥ 2.23; `reset` covers older ones and repos without commits. */
    fun unstageScript(paths: List<String>): String {
        val list = paths.joinToString(" ") { q(it) }
        return "git restore --staged -- $list 2>/dev/null || git reset -q -- $list 2>/dev/null || git rm -q --cached -- $list"
    }

    fun discardScript(file: GitFile): String =
        if (file.untracked) "rm -rf -- ${q(file.path)}" else "git checkout -- ${q(file.path)}"

    const val STAGE_ALL_SCRIPT = "git add -A"

    fun commitScript(message: String, amend: Boolean, stageAll: Boolean): String =
        (if (stageAll) "git add -A && " else "") +
            "printf '%s' ${q(b64(message))} | base64 -d | git commit -F -" + if (amend) " --amend" else ""

    /** Pushes, setting the upstream on the first push of a branch. */
    const val PUSH_SCRIPT = "if git rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then git push; " +
        "else git push -u origin HEAD; fi"

    const val PULL_SCRIPT = "git pull --ff-only"

    const val FETCH_SCRIPT = "git fetch --all --prune"

    const val INIT_SCRIPT = "git init && git status --short"
}
