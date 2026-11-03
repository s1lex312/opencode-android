package ai.opencode.android.data.config

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class ConfigVersion(val file: File, val createdAt: Long, val sizeBytes: Long, val source: ConfigHistory.Source)

/**
 * Previous versions of the in-app server's config, newest first. A version is the text a file had
 * right before something changed it, so every change can be undone: saves in the editor, edits the
 * app makes (provider or MCP removal, plugins), and rewrites by the server or the agent itself.
 */
class ConfigHistory(
    private val dir: File,
    private val maxVersions: Int = MAX_VERSIONS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Source(val tag: String) {
        /** Saved in Settings → opencode config. */
        EDITOR("editor"),

        /** Removed by the app: a provider, an MCP server, an agent, a plugin. */
        APP("app"),

        /** Rewritten outside the app's own writes: the server (API patches), the agent, a terminal. */
        EXTERNAL("external"),
        ;

        companion object {
            fun of(tag: String?): Source = entries.firstOrNull { it.tag == tag } ?: EXTERNAL
        }
    }

    @Synchronized
    fun versions(): List<ConfigVersion> =
        dir.listFiles()?.mapNotNull(::parse)?.sortedByDescending { it.createdAt }.orEmpty()

    fun read(version: ConfigVersion): String = version.file.readText()

    /** Keeps [text] as a version unless it equals the newest one. Returns whether it was stored. */
    @Synchronized
    fun keep(text: String, source: Source): Boolean {
        if (text.isBlank()) return false
        val newest = versions().firstOrNull()
        if (newest != null && runCatching { newest.file.readText() }.getOrNull() == text) return false
        dir.mkdirs()
        var at = clock()
        // Two changes within a millisecond still get distinct, ordered names.
        while (File(dir, name(at, source)).exists() || (newest != null && at <= newest.createdAt)) at++
        File(dir, name(at, source)).writeText(text)
        versions().drop(maxVersions).forEach { it.file.delete() }
        return true
    }

    private fun name(at: Long, source: Source) = "opencode-$at-${source.tag}.json"

    private fun parse(file: File): ConfigVersion? {
        if (!file.isFile || !file.name.endsWith(".json")) return null
        val match = NAME.matchEntire(file.name)
        if (match != null) {
            return ConfigVersion(file, match.groupValues[1].toLong(), file.length(), Source.of(match.groupValues[2]))
        }
        // 1.7.0 named versions by local time: opencode-20261103-141233.json, all from the editor.
        val legacy = LEGACY.matchEntire(file.name) ?: return null
        val at = runCatching { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(legacy.groupValues[1])?.time }.getOrNull()
        return ConfigVersion(file, at ?: file.lastModified(), file.length(), Source.EDITOR)
    }

    companion object {
        const val MAX_VERSIONS = 20
        private val NAME = Regex("""opencode-(\d{10,})-([a-z]+)\.json""")
        private val LEGACY = Regex("""opencode-(\d{8}-\d{6})\.json""")
    }
}
