package ai.opencode.android.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient JSON used for all opencode wire traffic.
 *
 * The server API is still evolving (v2 renamed most message fields), so the client reads payloads
 * defensively through the helpers below instead of binding to strict generated models. Unknown
 * fields are ignored and a missing field is never an error.
 */
val OpencodeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

fun parseJsonObjectOrNull(raw: String): JsonObject? =
    runCatching { OpencodeJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()

fun JsonObject.element(vararg path: String): JsonElement? {
    var current: JsonElement = this
    for (segment in path) {
        val obj = current as? JsonObject ?: return null
        current = obj[segment] ?: return null
    }
    return current
}

fun JsonObject.obj(vararg path: String): JsonObject? = element(*path) as? JsonObject

fun JsonObject.arr(vararg path: String): JsonArray? = element(*path) as? JsonArray

fun JsonObject.str(vararg path: String): String? {
    val element = element(*path) ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}

fun JsonObject.long(vararg path: String): Long? = str(*path)?.toLongOrNull()

fun JsonObject.int(vararg path: String): Int? = str(*path)?.toIntOrNull()

fun JsonObject.bool(vararg path: String): Boolean? {
    val value = str(*path) ?: return null
    return value.equals("true", ignoreCase = true) || value == "1"
}

fun JsonObject.strList(vararg path: String): List<String> {
    val array = arr(*path) ?: return emptyList()
    return array.mapNotNull { element ->
        val primitive = element as? JsonPrimitive ?: return@mapNotNull null
        if (primitive is JsonNull) null else primitive.content
    }
}

fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** Unwraps a transport envelope: v2 endpoints answer `{ "data": ... }`. */
fun JsonElement.unwrapData(): JsonElement {
    val obj = this as? JsonObject ?: return this
    return obj["data"] ?: this
}

fun JsonObject?.orEmptyObject(): JsonObject = this ?: JsonObject(emptyMap())

/** Concatenates the textual entries of a v2 tool `content` array. */
fun JsonArray.toolContentText(): String {
    val chunks = mutableListOf<String>()
    for (element in this) {
        val obj = element as? JsonObject ?: continue
        when (obj.str("type")) {
            "text" -> obj.str("text")?.let { chunks += it }
            "file" -> {
                val name = obj.str("name") ?: obj.str("uri") ?: "file"
                chunks += "[file] $name"
            }

            else -> obj.str("text")?.let { chunks += it }
        }
    }
    return chunks.joinToString("\n")
}

/** Renders arbitrary tool input for the collapsible card. */
fun JsonElement?.prettyOrEmpty(): String? {
    if (this == null || this is JsonNull) return null
    return try {
        PrettyJson.encode(this)
    } catch (_: Throwable) {
        toString()
    }
}

/**
 * Small deterministic pretty-printer for [JsonElement]. Implemented locally (instead of
 * `Json { prettyPrint = true }`) so tool cards get stable, bounded output and the code stays
 * free of serializer plumbing.
 */
object PrettyJson {

    fun encode(element: JsonElement, maxLength: Int = 8000): String {
        val sb = StringBuilder()
        write(element, sb, 0)
        val text = sb.toString()
        return if (text.length <= maxLength) text else text.take(maxLength) + "\n… (truncated)"
    }

    private fun write(element: JsonElement, out: StringBuilder, depth: Int) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(element.toString())
            is JsonArray -> {
                if (element.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                element.forEachIndexed { index, child ->
                    indent(out, depth + 1)
                    write(child, out, depth + 1)
                    if (index < element.size - 1) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append(']')
            }

            is JsonObject -> {
                if (element.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                val entries = element.entries.toList()
                entries.forEachIndexed { index, (key, value) ->
                    indent(out, depth + 1)
                    out.append('"').append(key.replace("\"", "\\\"")).append("\": ")
                    write(value, out, depth + 1)
                    if (index < entries.size - 1) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append('}')
            }
        }
    }

    private fun indent(out: StringBuilder, depth: Int) {
        for (i in 0 until depth) out.append("  ")
    }
}
