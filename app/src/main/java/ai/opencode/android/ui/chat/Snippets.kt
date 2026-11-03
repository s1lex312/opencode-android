package ai.opencode.android.ui.chat

/**
 * Saved prompts (`/name args`), expanded like opencode's own command templates:
 * `$ARGUMENTS` is everything after the name, `$1`..`$9` are its words (quotes group words).
 * A template without placeholders gets the arguments appended on a new paragraph.
 */
object Snippets {

    private val NAME = Regex("^[a-z0-9][a-z0-9_-]{0,39}$")
    private val POSITIONAL = Regex("\\$([1-9])")

    fun isValidName(name: String): Boolean = NAME.matches(name)

    /** Lowercases and replaces anything outside `[a-z0-9_-]`, for names typed with spaces. */
    fun normalizeName(raw: String): String =
        raw.trim().lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^a-z0-9_-]"), "").trimStart('-', '_').take(40)

    fun expand(body: String, arguments: String): String {
        val args = arguments.trim()
        val words = splitArguments(args)
        val hasPlaceholder = body.contains("\$ARGUMENTS") || POSITIONAL.containsMatchIn(body)
        var text = body.replace("\$ARGUMENTS", args)
        text = POSITIONAL.replace(text) { match -> words.getOrNull(match.groupValues[1].toInt() - 1).orEmpty() }
        if (!hasPlaceholder && args.isNotEmpty()) text = text.trimEnd() + "\n\n" + args
        return text.trim()
    }

    fun splitArguments(arguments: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in arguments) {
            when {
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '"' || ch == '\'') -> quote = ch
                quote == null && ch.isWhitespace() -> {
                    if (current.isNotEmpty()) words += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }
}
