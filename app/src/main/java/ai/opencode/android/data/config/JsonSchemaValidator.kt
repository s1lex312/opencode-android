package ai.opencode.android.data.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One problem found in a document: a JSON path such as `mcp.github.command` and what is wrong. */
data class SchemaError(val path: String, val message: String)

/**
 * The part of JSON Schema that config schemas actually use: `$ref` (local pointers), `type`,
 * `enum`, `const`, `properties`, `additionalProperties`, `required`, `items`, `anyOf` / `oneOf` /
 * `allOf`, `minimum` / `maximum`, `minLength`, `minItems`. Unknown keywords are ignored, so a newer
 * schema degrades to fewer checks instead of false errors.
 */
class JsonSchemaValidator(private val root: JsonObject) {

    fun validate(document: JsonElement, limit: Int = 50): List<SchemaError> {
        val errors = mutableListOf<SchemaError>()
        check(document, root, "", errors, limit, depth = 0)
        return errors
    }

    private fun check(value: JsonElement, schema: JsonElement, path: String, errors: MutableList<SchemaError>, limit: Int, depth: Int) {
        if (errors.size >= limit || depth > 64) return
        if (schema is JsonPrimitive) {
            if (schema.booleanOrNull == false) errors += SchemaError(path.ifEmpty { "$" }, "not allowed here")
            return
        }
        val s = schema as? JsonObject ?: return
        (s["\$ref"] as? JsonPrimitive)?.content?.let { ref ->
            val target = resolve(ref) ?: return
            check(value, target, path, errors, limit, depth + 1)
            if (s.keys.all { it == "\$ref" || it == "description" || it == "title" || it == "default" }) return
        }

        (s["anyOf"] as? JsonArray ?: s["oneOf"] as? JsonArray)?.let { branches ->
            val matches = branches.any { branch -> matches(value, branch, path, depth) }
            if (!matches) {
                errors += SchemaError(path.ifEmpty { "$" }, "does not match any allowed form (${describe(branches)})")
                return
            }
        }
        (s["allOf"] as? JsonArray)?.forEach { check(value, it, path, errors, limit, depth + 1) }

        s["type"]?.let { type ->
            val allowed = when (type) {
                is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOf(type.content)
                else -> emptyList()
            }
            if (allowed.isNotEmpty() && allowed.none { typeMatches(value, it) }) {
                errors += SchemaError(path.ifEmpty { "$" }, "expected ${allowed.joinToString(" or ")}, got ${typeName(value)}")
                return
            }
        }
        (s["enum"] as? JsonArray)?.let { options ->
            if (options.none { it == value }) errors += SchemaError(path.ifEmpty { "$" }, "must be one of ${options.joinToString(", ") { it.toString() }}")
        }
        s["const"]?.let { constant ->
            if (constant != value) errors += SchemaError(path.ifEmpty { "$" }, "must be $constant")
        }

        when (value) {
            is JsonObject -> checkObject(value, s, path, errors, limit, depth)
            is JsonArray -> {
                (s["minItems"] as? JsonPrimitive)?.longOrNull?.let { if (value.size < it) errors += SchemaError(path, "needs at least $it items") }
                s["items"]?.takeIf { it is JsonObject || it is JsonPrimitive }?.let { items ->
                    value.forEachIndexed { index, item -> check(item, items, "$path[$index]", errors, limit, depth + 1) }
                }
            }
            is JsonPrimitive -> {
                if (value.isString) {
                    (s["minLength"] as? JsonPrimitive)?.longOrNull?.let { if (value.content.length < it) errors += SchemaError(path, "shorter than $it characters") }
                } else {
                    value.doubleOrNull?.let { number ->
                        (s["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { if (number < it) errors += SchemaError(path, "must be ≥ ${trim(it)}") }
                        (s["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { if (number > it) errors += SchemaError(path, "must be ≤ ${trim(it)}") }
                        (s["exclusiveMinimum"] as? JsonPrimitive)?.doubleOrNull?.let { if (number <= it) errors += SchemaError(path, "must be > ${trim(it)}") }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun checkObject(value: JsonObject, s: JsonObject, path: String, errors: MutableList<SchemaError>, limit: Int, depth: Int) {
        val properties = s["properties"] as? JsonObject
        (s["required"] as? JsonArray)?.forEach { key ->
            val name = (key as? JsonPrimitive)?.content ?: return@forEach
            if (name !in value) errors += SchemaError(join(path, name), "required")
        }
        val additional = s["additionalProperties"]
        val patterns = (s["patternProperties"] as? JsonObject)?.mapNotNull { (pattern, sub) ->
            runCatching { Regex(pattern) to sub }.getOrNull()
        }.orEmpty()
        value.forEach { (key, child) ->
            val childPath = join(path, key)
            val declared = properties?.get(key)
            val patterned = patterns.firstOrNull { it.first.containsMatchIn(key) }?.second
            when {
                declared != null -> check(child, declared, childPath, errors, limit, depth + 1)
                patterned != null -> check(child, patterned, childPath, errors, limit, depth + 1)
                // `$schema` is how editors find the schema; every config may carry it.
                key == "\$schema" -> Unit
                additional is JsonPrimitive && additional.booleanOrNull == false ->
                    errors += SchemaError(childPath, "unknown key${suggest(key, properties?.keys.orEmpty())}")
                additional is JsonObject -> check(child, additional, childPath, errors, limit, depth + 1)
            }
        }
    }

    private fun matches(value: JsonElement, schema: JsonElement, path: String, depth: Int): Boolean {
        val probe = mutableListOf<SchemaError>()
        check(value, schema, path, probe, 1, depth + 1)
        return probe.isEmpty()
    }

    /** `#/$defs/Agent` or `#/definitions/Agent` (any local JSON pointer). */
    private fun resolve(ref: String): JsonElement? {
        if (!ref.startsWith("#")) return null
        var current: JsonElement = root
        ref.removePrefix("#").split('/').filter { it.isNotEmpty() }.forEach { part ->
            val key = part.replace("~1", "/").replace("~0", "~")
            current = when (val node = current) {
                is JsonObject -> node[key] ?: return null
                is JsonArray -> key.toIntOrNull()?.let { node.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun typeMatches(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "null" -> value is JsonNull
        else -> true
    }

    private fun typeName(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.booleanOrNull != null -> "boolean"
            else -> "number"
        }
    }

    private fun describe(branches: JsonArray): String = branches.mapNotNull { branch ->
        val obj = branch as? JsonObject ?: return@mapNotNull null
        val target = (obj["\$ref"] as? JsonPrimitive)?.content?.let { resolve(it) as? JsonObject } ?: obj
        (target["type"] as? JsonPrimitive)?.content ?: (target["const"])?.toString() ?: (target["enum"] as? JsonArray)?.joinToString("|")
    }.distinct().joinToString(", ").ifEmpty { "see schema" }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    private fun trim(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun suggest(key: String, known: Set<String>): String {
        val best = known.minByOrNull { distance(it.lowercase(), key.lowercase()) } ?: return ""
        return if (distance(best.lowercase(), key.lowercase()) <= 2) ", did you mean \"$best\"?" else ""
    }

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1)
                previous = temp
            }
        }
        return dp[b.length]
    }
}

/** opencode accepts JSONC: comments and trailing commas. Strips both, keeping strings intact. */
object Jsonc {
    fun strip(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                if (c == '\\' && i + 1 < text.length) {
                    out.append(text[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            when {
                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                c == ',' -> {
                    var j = i + 1
                    while (j < text.length && text[j].isWhitespace()) j++
                    if (j < text.length && (text[j] == '}' || text[j] == ']')) i++ else {
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
