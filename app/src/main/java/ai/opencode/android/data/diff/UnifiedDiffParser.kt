package ai.opencode.android.data.diff

/** A parsed unified-diff file section. */
data class DiffFile(
    val oldPath: String?,
    val newPath: String?,
    val hunks: List<DiffHunk>,
    val additions: Int = 0,
    val deletions: Int = 0,
    val isBinary: Boolean = false,
) {
    /** Display path: prefer the post-image path, fall back to the pre-image one. */
    val path: String get() = newPath ?: oldPath ?: "unknown"

    val hasChanges: Boolean get() = hunks.isNotEmpty() || isBinary
}

data class DiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

enum class DiffLineKind { CONTEXT, ADDED, REMOVED, NOTE }

data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

/**
 * A deliberately small, dependency-free parser for the unified diffs that opencode returns
 * (`FileDiff.info.patch`). It handles git-style preambles, multiple file sections, multiple
 * hunks per file, `\ No newline at end of file` markers and "binary files differ" notices.
 *
 * The parser never throws: malformed input degrades into best-effort output so a single bad
 * patch cannot take the diff screen down.
 */
object UnifiedDiffParser {

    private val HUNK_HEADER = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")

    fun parse(patch: String): List<DiffFile> {
        val files = mutableListOf<DiffFile>()
        var oldPath: String? = null
        var newPath: String? = null
        var hunks = mutableListOf<DiffHunk>()
        var additions = 0
        var deletions = 0
        var binary = false

        var currentHunkHeader: String? = null
        var oldStart = 0
        var oldCount = 0
        var newStart = 0
        var newCount = 0
        var oldLine = 0
        var newLine = 0
        var hunkLines = mutableListOf<DiffLine>()

        fun closeHunk() {
            val header = currentHunkHeader ?: return
            hunks += DiffHunk(header, oldStart, oldCount, newStart, newCount, hunkLines.toList())
            hunkLines = mutableListOf()
            currentHunkHeader = null
        }

        fun closeFile() {
            closeHunk()
            if (oldPath != null || newPath != null || hunks.isNotEmpty() || binary) {
                files += DiffFile(
                    oldPath = oldPath,
                    newPath = newPath,
                    hunks = hunks.toList(),
                    additions = additions,
                    deletions = deletions,
                    isBinary = binary,
                )
            }
            oldPath = null
            newPath = null
            hunks = mutableListOf()
            additions = 0
            deletions = 0
            binary = false
        }

        val lines = patch.split("\n")
        var index = 0
        while (index < lines.size) {
            val raw = lines[index].removeSuffix("\r")
            when {
                raw.startsWith("diff --git ") -> {
                    closeFile()
                    val gitPaths = parseGitHeader(raw)
                    oldPath = gitPaths.first
                    newPath = gitPaths.second
                }

                raw.startsWith("--- ") -> {
                    // A new file section can start without a `diff --git` preamble.
                    if (currentHunkHeader == null && hunks.isNotEmpty()) closeFile()
                    oldPath = normalizePath(raw.removePrefix("--- "))
                }

                raw.startsWith("+++ ") -> {
                    newPath = normalizePath(raw.removePrefix("+++ "))
                }

                raw.startsWith("@@") -> {
                    closeHunk()
                    val match = HUNK_HEADER.matchEntire(raw)
                    if (match == null) {
                        currentHunkHeader = raw
                        oldStart = 0; oldCount = 0; newStart = 0; newCount = 0
                    } else {
                        currentHunkHeader = raw
                        oldStart = match.groupValues[1].toIntOrNull() ?: 0
                        oldCount = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                        newStart = match.groupValues[3].toIntOrNull() ?: 0
                        newCount = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                    }
                    oldLine = oldStart
                    newLine = newStart
                }

                raw.startsWith("Binary files ") || raw == "GIT binary patch" -> {
                    binary = true
                }

                raw.startsWith("\\") -> {
                    if (currentHunkHeader != null) {
                        hunkLines += DiffLine(DiffLineKind.NOTE, raw.removePrefix("\\ ").trim())
                    }
                }

                raw.startsWith("+") -> {
                    additions++
                    hunkLines += DiffLine(DiffLineKind.ADDED, raw.substring(1), newLineNumber = newLine)
                    newLine++
                }

                raw.startsWith("-") -> {
                    deletions++
                    hunkLines += DiffLine(DiffLineKind.REMOVED, raw.substring(1), oldLineNumber = oldLine)
                    oldLine++
                }

                raw.startsWith(" ") -> {
                    hunkLines += DiffLine(
                        DiffLineKind.CONTEXT,
                        raw.substring(1),
                        oldLineNumber = oldLine,
                        newLineNumber = newLine,
                    )
                    oldLine++
                    newLine++
                }

                raw.isEmpty() -> {
                    // Empty line inside a hunk means a context line whose trailing space was stripped.
                    if (currentHunkHeader != null) {
                        hunkLines += DiffLine(
                            DiffLineKind.CONTEXT,
                            "",
                            oldLineNumber = oldLine,
                            newLineNumber = newLine,
                        )
                        oldLine++
                        newLine++
                    }
                }

                // Metadata lines (index, new file mode, similarity index, ...) are ignored.
                else -> Unit
            }
            index++
        }
        closeFile()
        return files
    }

    private fun parseGitHeader(line: String): Pair<String?, String?> {
        val rest = line.removePrefix("diff --git ").trim()
        val parts = rest.split(" ")
        if (parts.size < 2) return null to null
        return normalizePath(parts[0]) to normalizePath(parts[1])
    }

    private fun normalizePath(raw: String): String? {
        var path = raw.trim()
        if (path == "/dev/null") return null
        // Strip the trailing timestamp that plain `diff -u` appends.
        if (path.contains("\t")) path = path.substringBefore("\t")
        if (path.startsWith("a/") || path.startsWith("b/")) path = path.substring(2)
        return path.ifEmpty { null }
    }
}
