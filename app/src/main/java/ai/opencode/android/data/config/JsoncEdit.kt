package ai.opencode.android.data.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Text-level edits of a JSON/JSONC document that keep everything else byte for byte: comments,
 * indentation, key order. opencode writes its global config as `opencode.jsonc`, usually with a
 * `$schema` URL in it, so re-serialising the parsed tree would throw away the user's comments.
 */
object JsoncEdit {

    /**
     * Removes the member at [path] (e.g. `["provider", "my-llm"]`). Null when the text does not
     * parse or the path is absent; the result is checked to parse and to no longer contain it.
     */
    fun remove(text: String, path: List<String>): String? {
        require(path.isNotEmpty())
        val scanner = Scanner(text)
        val result = runCatching {
            var start = scanner.skipTrivia(0)
            if (scanner.at(start) != '{') return null
            var members = scanner.members(start)
            for ((depth, key) in path.withIndex()) {
                val index = members.indexOfFirst { it.key == key }
                if (index < 0) return null
                val member = members[index]
                if (depth == path.lastIndex) return@runCatching cut(text, members, index)
                start = member.valueStart
                if (scanner.at(start) != '{') return null
                members = scanner.members(start)
            }
            null
        }.getOrNull() ?: return null
        return result.takeIf { stillValidWithout(it, path) }
    }

    private fun cut(text: String, members: List<Member>, index: Int): String {
        val member = members[index]
        val ownEnd = if (member.comma >= 0) member.comma + 1 else member.valueEnd
        if (index < members.lastIndex) {
            // Not the last one: drop the whole line(s) it occupies, including its comma.
            val start = lineStartIfBlankBefore(text, member.keyStart)
            val end = lineEndIfBlankAfter(text, ownEnd)
            return text.removeRange(start, end)
        }
        val previous = members.getOrNull(index - 1)
        if (previous != null && previous.comma >= 0) {
            // The last one: take the previous member's comma with it so no trailing comma is left.
            return text.removeRange(previous.comma, ownEnd)
        }
        return text.removeRange(member.keyStart, ownEnd)
    }

    private fun lineStartIfBlankBefore(text: String, at: Int): Int {
        var i = at
        while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
        return if (i == 0 || text[i - 1] == '\n') i else at
    }

    private fun lineEndIfBlankAfter(text: String, at: Int): Int {
        var i = at
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
        return if (i < text.length && text[i] == '\n') i + 1 else at
    }

    private fun stillValidWithout(text: String, path: List<String>): Boolean = runCatching {
        var node = Json.parseToJsonElement(Jsonc.strip(text)) as? JsonObject ?: return false
        for (key in path.dropLast(1)) node = node[key] as? JsonObject ?: return true
        path.last() !in node
    }.getOrDefault(false)

    private data class Member(val key: String, val keyStart: Int, val valueStart: Int, val valueEnd: Int, val comma: Int)

    private class Scanner(private val text: String) {

        fun at(i: Int): Char? = text.getOrNull(i)

        fun skipTrivia(from: Int): Int {
            var i = from
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && at(i + 1) == '/' -> while (i < text.length && text[i] != '\n') i++
                    c == '/' && at(i + 1) == '*' -> {
                        val end = text.indexOf("*/", i + 2)
                        if (end < 0) throw IllegalStateException("unterminated comment")
                        i = end + 2
                    }
                    else -> return i
                }
            }
            return i
        }

        fun stringEnd(start: Int): Int {
            check(text[start] == '"')
            var i = start + 1
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i += 2
                    '"' -> return i + 1
                    else -> i++
                }
            }
            throw IllegalStateException("unterminated string")
        }

        fun valueEnd(start: Int): Int {
            return when (at(start)) {
                '"' -> stringEnd(start)
                '{', '[' -> {
                    var depth = 0
                    var i = start
                    while (i < text.length) {
                        val c = text[i]
                        when {
                            c == '"' -> { i = stringEnd(i); continue }
                            c == '/' && (at(i + 1) == '/' || at(i + 1) == '*') -> { i = skipTrivia(i); continue }
                            c == '{' || c == '[' -> depth++
                            c == '}' || c == ']' -> if (--depth == 0) return i + 1
                        }
                        i++
                    }
                    throw IllegalStateException("unbalanced brackets")
                }
                null -> throw IllegalStateException("value expected")
                else -> {
                    var i = start
                    while (i < text.length && text[i] !in ",}] \t\r\n/") i++
                    check(i > start) { "value expected" }
                    i
                }
            }
        }

        fun members(objectStart: Int): List<Member> {
            check(text[objectStart] == '{')
            val members = mutableListOf<Member>()
            var i = objectStart + 1
            while (true) {
                i = skipTrivia(i)
                when (at(i)) {
                    '}' -> return members
                    '"' -> Unit
                    else -> throw IllegalStateException("key expected")
                }
                val keyStart = i
                val keyEnd = stringEnd(i)
                val key = (Json.parseToJsonElement(text.substring(keyStart, keyEnd)) as JsonPrimitive).content
                i = skipTrivia(keyEnd)
                check(at(i) == ':') { "colon expected" }
                val valueStart = skipTrivia(i + 1)
                val valueEnd = valueEnd(valueStart)
                i = skipTrivia(valueEnd)
                val comma = if (at(i) == ',') i.also { i++ } else -1
                members += Member(key, keyStart, valueStart, valueEnd, comma)
            }
        }
    }
}
