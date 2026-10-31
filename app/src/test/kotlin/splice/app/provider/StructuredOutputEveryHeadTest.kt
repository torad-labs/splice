// Goal: when Claude Code asks claudex, an OpenAI API-key head or an xAI Responses head for an answer in a JSON schema,
// through `output_config.format` or through its StructuredOutput tool, the head answers instead of drawing a 400, and
// the answer the client receives follows the schema the client asked for. Anyone can tell from the request a head
// sends and the answer it relays: `text.format` asks for strict only over a schema inside the strict subset that
// vendor documents, rewritten into it where the subset asks for closed objects and every key required (an optional
// field made nullable), and otherwise sends the caller's schema as written with strict false. Strict is asked for
// whenever the vendor can hold the schema. Whatever the backend answers within the schema it was sent, the client
// gets an answer that is valid against its own schema.
//
// Oct 27, 14:01 CT: claudex answered 400 "Invalid schema for response_format 'structured_output' ...
// 'additionalProperties' is required to be supplied and to be false" twice, to a schema whose objects did not close.
// OpenAI documents the subset (https://developers.openai.com/api/docs/guides/structured-outputs, "Supported schemas"):
// the root is an object and not anyOf, every object sets additionalProperties false and lists every key in required,
// an optional field is a union with null, recursion through `#` or $defs is allowed, and allOf, not, if/then/else,
// oneOf, and keywords outside its list are refused under strict. xAI documents its own
// (https://docs.x.ai/docs/guides/structured-outputs): objects close by default, unlisted fields stay optional,
// $ref must not be circular, maxContains/minContains and array-form items are refused, and not, if/then/else and
// allOf beyond one schema are not enforced.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.index.WireBlockIndex
import splice.core.parse.AnthropicParse
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.topology.TopologyLoader
import splice.upstream.Provider
import splice.upstream.TurnSignals
import splice.upstream.sse.WireSink
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val STRICT_RUNS = 250
private const val ON_CODEX = "claude-codex"
private const val ON_OPENAI = "claude-openai"
private const val ON_XAI = "claude-xai"

/** How deep an answer goes before it takes the way out of a recursive schema: null, an empty list, no optional field. */
private const val ANSWER_ENDING = 4

/** The draws of a value that nest no further, first in the draw table. */
private const val SHALLOW_DRAWS = 11

/** Each head and the model its requests name. */
private val STRICT_MODELS = mapOf(ON_CODEX to "gpt-5.5", ON_OPENAI to "gpt-5.5", ON_XAI to "grok-4.5")

/** Where Claude Code puts the schema it wants the answer in. */
private enum class AskedIn { TOOL, FORCED_TOOL, OUTPUT_CONFIG, BOTH }

/** A drawn schema, whether OpenAI's and xAI's strict modes can hold every feature in it, and whether any object in it
 *  leaves a property optional. */
private data class DrawnSchema(
    val schema: JsonObject,
    val openAiHolds: Boolean,
    val xaiHolds: Boolean,
    val optional: Boolean,
)

/** JSON schemas of the kinds callers write: nested objects open, closed or unstated, optional fields, scalars with
 *  constraints and formats, enums, consts, nullable types, arrays, anyOf/oneOf/allOf, $defs references, free-form maps,
 *  and recursive ones, through `#` and through a $defs entry. */
private class SchemaDraw(private val random: Random) {
    private var field = 0
    private var openAi = true
    private var xai = true
    private var optional = false
    private val defs = linkedMapOf<String, JsonObject>()
    private var named = 0

    fun draw(): DrawnSchema {
        val root = when (random.nextInt(10)) {
            0 -> recursiveRoot()
            1 -> buildJsonObject {
                openAi = false
                put("anyOf", JsonArray(listOf(objectSchema(1), objectSchema(1))))
            }
            else -> objectSchema(0)
        }
        val withDefs = if (defs.isEmpty()) root else JsonObject(root + ("\$defs" to JsonObject(defs)))
        val schema = if (random.nextInt(4) == 0) {
            JsonObject(withDefs + ("\$schema" to JsonPrimitive("http://json-schema.org/draft-07/schema#")))
        } else {
            withDefs
        }
        return DrawnSchema(schema, openAi, xai, optional)
    }

    private fun objectSchema(depth: Int): JsonObject {
        val names = List(random.nextInt(1, 5)) { "field_${field++}" }
        val required = names.filter { random.nextInt(3) > 0 }
        if (required.size < names.size) optional = true
        return buildJsonObject {
            put("type", "object")
            if (random.nextInt(4) == 0) put("description", "an object at depth $depth")
            if (random.nextInt(6) == 0) put("title", "Shape$depth")
            put("properties", JsonObject(names.associateWith { value(depth) }))
            if (required.isNotEmpty() || random.nextBoolean()) put("required", strings(required))
            when (random.nextInt(3)) {
                0 -> Unit
                1 -> put("additionalProperties", false)
                else -> put("additionalProperties", true)
            }
        }
    }

    /** One draw per kind of value. The first [SHALLOW_DRAWS] nest no further, so past depth 3 only they are drawn. */
    private val draws: List<(Int) -> JsonObject> = listOf(
        { _ -> typed("string") },
        { _ -> typed("integer").let { if (random.nextBoolean()) JsonObject(it + bounds()) else it } },
        { _ -> typed("number") },
        { _ -> typed("boolean") },
        { _ ->
            buildJsonObject {
                put("type", "string")
                put("enum", strings(listOf("low", "medium", "high")))
            }
        },
        { _ -> buildJsonObject { put("type", strings(listOf("string", "null"))) } },
        { _ -> formatted() },
        { _ ->
            buildJsonObject {
                openAi = false
                put("type", "string")
                put("minLength", 1)
                put("maxLength", 40)
            }
        },
        { _ -> buildJsonObject { put("const", "fixed") } },
        { depth -> definition(depth) },
        { _ ->
            buildJsonObject {
                openAi = false
                xai = false
                put("type", "object")
                putJsonObject("additionalProperties") { put("type", "string") }
            }
        },
        { depth ->
            buildJsonObject {
                put("type", "array")
                put("items", objectSchema(depth + 1))
                if (random.nextBoolean()) put("maxItems", 5)
            }
        },
        { _ ->
            buildJsonObject {
                openAi = false
                xai = false
                put("type", "array")
                put("items", typed("string"))
                put("uniqueItems", true)
            }
        },
        { depth -> buildJsonObject { put("anyOf", JsonArray(listOf(objectSchema(depth + 1), typed("string")))) } },
        { _ ->
            buildJsonObject {
                openAi = false
                put("oneOf", JsonArray(listOf(typed("string"), typed("integer"))))
            }
        },
        { depth ->
            buildJsonObject {
                openAi = false
                xai = false
                put("allOf", JsonArray(listOf(objectSchema(depth + 1))))
            }
        },
    )

    private fun value(depth: Int): JsonObject =
        draws[random.nextInt(if (depth < 3) draws.size else SHALLOW_DRAWS)](depth)

    private fun formatted(): JsonObject = buildJsonObject {
        put("type", "string")
        if (random.nextBoolean()) {
            put("format", "date-time")
        } else {
            openAi = false
            put("format", "uri")
        }
    }

    private fun definition(depth: Int): JsonObject {
        // Named before its body is drawn, so a definition drawn inside it never takes the same name.
        val name = "Def${named++}"
        defs[name] = objectSchema(depth + 1)
        return buildJsonObject { put("\$ref", "#/\$defs/$name") }
    }

    /** A tree whose children are the root again, or a list whose nodes reach the next through $defs. */
    private fun recursiveRoot(): JsonObject {
        xai = false
        return if (random.nextBoolean()) {
            val childrenRequired = random.nextBoolean()
            if (!childrenRequired) optional = true
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("children") {
                        put("type", "array")
                        putJsonObject("items") { put("\$ref", "#") }
                    }
                }
                put("required", strings(if (childrenRequired) listOf("name", "children") else listOf("name")))
            }
        } else {
            optional = true
            defs["node"] = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("value") { put("type", "string") }
                    putJsonObject("next") {
                        put(
                            "anyOf",
                            JsonArray(listOf(buildJsonObject { put("\$ref", "#/\$defs/node") }, typed("null"))),
                        )
                    }
                }
                put("required", strings(listOf("value", "next")))
                put("additionalProperties", false)
            }
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("head") { put("\$ref", "#/\$defs/node") }
                    putJsonObject("tail") { put("\$ref", "#/\$defs/node") }
                }
                put("required", strings(listOf("head")))
            }
        }
    }

    private fun bounds(): Map<String, JsonElement> =
        mapOf("minimum" to JsonPrimitive(0), "maximum" to JsonPrimitive(100))
}

private fun typed(type: String): JsonObject = buildJsonObject { put("type", type) }

private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.types(): List<String> = when (val type = this["type"]) {
    is JsonPrimitive -> listOfNotNull(type.takeIf { it.isString }?.content)
    is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
    is JsonObject, null -> emptyList()
}

private fun JsonObject.names(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

/** The schema a `$ref` inside [root] names: `#`, `#/$defs/<name>` or `#/definitions/<name>`. */
private fun resolve(root: JsonObject, ref: String): JsonObject = when {
    ref == "#" -> root
    ref.startsWith("#/\$defs/") -> root.getValue("\$defs").jsonObject.getValue(ref.removePrefix("#/\$defs/")).jsonObject
    else -> root.getValue("definitions").jsonObject.getValue(ref.removePrefix("#/definitions/")).jsonObject
}

/** OpenAI's strict subset as its Structured Outputs guide states it. */
private object OpenAiStrictSubset {
    private val keywords = setOf(
        "type", "properties", "required", "additionalProperties", "items", "enum", "const", "anyOf", "\$ref", "\$defs",
        "definitions", "description", "title", "pattern", "format", "multipleOf", "maximum", "exclusiveMaximum",
        "minimum", "exclusiveMinimum", "minItems", "maxItems",
    )
    private val formats = setOf("date-time", "time", "date", "duration", "email", "hostname", "ipv4", "ipv6", "uuid")
    private const val MAX_LEVELS = 10

    fun holds(schema: JsonObject): Boolean =
        schema.types() == listOf("object") && "anyOf" !in schema && node(schema, level = 1)

    private fun node(schema: JsonObject, level: Int): Boolean {
        val admitted = keywords.containsAll(schema.keys) && level <= MAX_LEVELS && schema.str("format").let {
            it == null || it in formats
        }
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val deeper = if ("object" in schema.types()) level + 1 else level
        val children = properties.values + listOfNotNull(schema["items"]) +
            (schema["anyOf"] as? JsonArray).orEmpty() + (schema["\$defs"] as? JsonObject).orEmpty().values
        return admitted && closed(schema) && children.all { it is JsonObject && node(it, deeper) }
    }

    /** Not an object, or one that sets additionalProperties false and lists every property in required. */
    private fun closed(schema: JsonObject): Boolean = "object" !in schema.types() || (
        schema["additionalProperties"] == JsonPrimitive(false) &&
            schema.names("required").toSet() == (schema["properties"] as? JsonObject).orEmpty().keys
        )
}

/** xAI's strict subset as its Structured Outputs guide states it: what it refuses, what it does not enforce, and no
 *  circular reference. */
private object XaiStrictSubset {
    private val unenforced = setOf("not", "if", "then", "else", "maxContains", "minContains")

    fun holds(schema: JsonObject): Boolean = !circular(schema) && node(schema)

    private fun node(schema: JsonObject): Boolean {
        val properties = (schema["properties"] as? JsonObject).orEmpty().values
        val plain = schema.keys.none { it in unenforced } && schema["items"] !is JsonArray &&
            properties.all { it is JsonObject } && (schema["allOf"] as? JsonArray).orEmpty().size <= 1 &&
            (schema["enum"] as? JsonArray)?.isEmpty() != true && (schema["anyOf"] as? JsonArray)?.isEmpty() != true
        val children = properties + listOfNotNull(schema["items"] as? JsonObject) +
            listOf("anyOf", "oneOf", "allOf").flatMap { (schema[it] as? JsonArray).orEmpty() } +
            (schema["\$defs"] as? JsonObject).orEmpty().values
        return plain && children.all { it is JsonObject && node(it) }
    }

    /** Whether a reference reaches, through further references, the schema it sits in. */
    private fun circular(root: JsonObject): Boolean {
        val defs = (root["\$defs"] as? JsonObject).orEmpty()
        fun refs(schema: JsonElement?): List<String> = when (schema) {
            is JsonObject -> listOfNotNull(schema.str("\$ref")) +
                schema.filterKeys { it != "\$defs" }.values.flatMap(::refs)
            is JsonArray -> schema.flatMap(::refs)
            is JsonPrimitive, null -> emptyList()
        }
        if ("#" in refs(root)) return true
        fun reaches(from: String, target: String, seen: Set<String>): Boolean =
            refs(defs[from]).map { it.removePrefix("#/\$defs/") }.any { next ->
                next == target || (next !in seen && reaches(next, target, seen + next))
            }
        return defs.keys.any { reaches(it, it, setOf(it)) }
    }
}

/** A value valid against a schema, drawn the way a model answering it might: optional fields present or not, each
 *  nullable one null at times, every union branch in turn, and recursion that ends. */
private class SchemaAnswer(private val root: JsonObject, private val random: Random) {
    fun of(schema: JsonObject, depth: Int = 0): JsonElement {
        val ref = schema.str("\$ref") ?: return fixed(schema) ?: union(schema, depth) ?: byType(schema, depth)
        return of(resolve(root, ref), depth + 1)
    }

    /** The const, or one of the enum values. */
    private fun fixed(schema: JsonObject): JsonElement? =
        schema["const"] ?: (schema["enum"] as? JsonArray)?.let { it[random.nextInt(it.size)] }

    /** An answer to one branch of an anyOf or oneOf, or to an allOf's one schema. */
    private fun union(schema: JsonObject, depth: Int): JsonElement? {
        val branches = (schema["anyOf"] as? JsonArray) ?: (schema["oneOf"] as? JsonArray)
        val all = (schema["allOf"] as? JsonArray)?.first()?.jsonObject
        return when {
            branches != null -> of(branch(branches.map { it.jsonObject }, depth), depth)
            all != null -> of(all, depth)
            else -> null
        }
    }

    /** Null past the answer's ending where the schema allows it, otherwise one of its types. */
    private fun typeOf(schema: JsonObject, depth: Int): String {
        val types = schema.types()
        return if (depth > ANSWER_ENDING && "null" in types) "null" else types[random.nextInt(types.size)]
    }

    private fun byType(schema: JsonObject, depth: Int): JsonElement =
        when (typeOf(schema, depth)) {
            "object" -> objectOf(schema, depth)
            "array" -> arrayOf(schema, depth)
            "string" -> JsonPrimitive(stringOf(schema))
            "integer" -> JsonPrimitive(random.nextInt(0, 101))
            "number" -> JsonPrimitive(random.nextInt(0, 1000) / 10.0)
            "boolean" -> JsonPrimitive(random.nextBoolean())
            else -> JsonNull
        }

    private fun branch(branches: List<JsonObject>, depth: Int): JsonObject =
        branches.firstOrNull { depth > ANSWER_ENDING && it.types() == listOf("null") }
            ?: branches[random.nextInt(branches.size)]

    private fun objectOf(schema: JsonObject, depth: Int): JsonObject {
        val required = schema.names("required").toSet()
        val properties = (schema["properties"] as? JsonObject).orEmpty()
            .filterKeys { it in required || (depth <= ANSWER_ENDING && random.nextBoolean()) }
            .mapValues { (_, value) -> of(value.jsonObject, depth + 1) }
        val extra = (schema["additionalProperties"] as? JsonObject)?.takeIf { random.nextBoolean() }
            ?.let { mapOf("extra_key" to of(it, depth + 1)) }.orEmpty()
        return JsonObject(properties + extra)
    }

    private fun arrayOf(schema: JsonObject, depth: Int): JsonArray {
        val items = schema["items"]?.jsonObject ?: return JsonArray(emptyList())
        val count = if (depth > ANSWER_ENDING) 0 else random.nextInt(0, 3)
        val drawn = List(count) { of(items, depth + 1) }
        return JsonArray(if (schema["uniqueItems"] == JsonPrimitive(true)) drawn.distinct() else drawn)
    }

    private fun stringOf(schema: JsonObject): String = when (schema.str("format")) {
        "date-time" -> "2026-10-27T14:01:00Z"
        "uri" -> "https://example.com/review"
        else -> "answer ${random.nextInt(100)}"
    }
}

/** Whether a value is valid against a schema, for the keywords these schemas use. */
private class SchemaCheck(private val root: JsonObject) {
    fun valid(value: JsonElement, schema: JsonObject): Boolean {
        val ref = schema.str("\$ref")
        if (ref != null) return valid(value, resolve(root, ref))
        val types = schema.types()
        val typed = types.isEmpty() || types.any { type -> typeChecks[type]?.invoke(value) == true }
        return fixedValid(value, schema) && unionsValid(value, schema) && typed && shape(value, schema)
    }

    private fun fixedValid(value: JsonElement, schema: JsonObject): Boolean {
        val enum = schema["enum"] as? JsonArray
        return (schema["const"] == null || schema["const"] == value) && (enum == null || value in enum)
    }

    private fun unionsValid(value: JsonElement, schema: JsonObject): Boolean {
        val anyOf = (schema["anyOf"] as? JsonArray)?.map { it.jsonObject }
        val oneOf = (schema["oneOf"] as? JsonArray)?.map { it.jsonObject }
        val allOf = (schema["allOf"] as? JsonArray).orEmpty().map { it.jsonObject }
        return (anyOf == null || anyOf.any { valid(value, it) }) &&
            (oneOf == null || oneOf.count { valid(value, it) } == 1) && allOf.all { valid(value, it) }
    }

    private fun shape(value: JsonElement, schema: JsonObject): Boolean = when (value) {
        is JsonObject -> objectValid(value, schema)
        is JsonArray -> arrayValid(value, schema)
        is JsonPrimitive -> scalarValid(value, schema)
    }

    private fun objectValid(value: JsonObject, schema: JsonObject): Boolean {
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val extra = schema["additionalProperties"]
        return value.keys.containsAll(schema.names("required")) && value.all { (key, element) ->
            when {
                key in properties -> valid(element, properties.getValue(key).jsonObject)
                extra == JsonPrimitive(false) -> false
                extra is JsonObject -> valid(element, extra)
                else -> true
            }
        }
    }

    private fun arrayValid(value: JsonArray, schema: JsonObject): Boolean {
        val items = schema["items"] as? JsonObject
        val max = (schema["maxItems"] as? JsonPrimitive)?.longOrNull
        val unique = schema["uniqueItems"] != JsonPrimitive(true) || value.distinct().size == value.size
        return (items == null || value.all { valid(it, items) }) && (max == null || value.size <= max) && unique
    }

    /** A string within minLength and maxLength, a number within minimum and maximum. */
    private fun scalarValid(value: JsonPrimitive, schema: JsonObject): Boolean = when {
        value is JsonNull -> true
        value.isString -> within(value.content.length.toDouble(), schema, "minLength", "maxLength")
        else -> within(value.doubleOrNull, schema, "minimum", "maximum")
    }

    private fun within(measure: Double?, schema: JsonObject, least: String, most: String): Boolean {
        val min = (schema[least] as? JsonPrimitive)?.doubleOrNull
        val max = (schema[most] as? JsonPrimitive)?.doubleOrNull
        return measure == null || ((min == null || measure >= min) && (max == null || measure <= max))
    }

    /** Whether a value is of each JSON Schema type. */
    private val typeChecks: Map<String, (JsonElement) -> Boolean> = mapOf(
        "object" to { it is JsonObject },
        "array" to { it is JsonArray },
        "null" to { it is JsonNull },
        "string" to { (it as? JsonPrimitive)?.isString == true },
        "boolean" to { bare(it)?.booleanOrNull != null },
        "integer" to { bare(it)?.longOrNull != null },
        "number" to { bare(it)?.doubleOrNull != null },
    )

    /** A JSON number or boolean: a primitive neither null nor a string. */
    private fun bare(value: JsonElement): JsonPrimitive? =
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }
}

/** What the client receives: the text of the answer and the input of each tool call, by tool name. */
private class AnswerSink : WireSink {
    val text = StringBuilder()
    val toolInput = linkedMapOf<String, StringBuilder>()
    private val toolAt = mutableMapOf<Int, String>()
    private var next = 0

    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++).also {
        toolAt[it.value] = name
        if (name !in toolInput) toolInput[name] = StringBuilder()
    }
    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        this.text.append(text)
    }
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        toolAt[index.value]?.let { toolInput.getValue(it).append(partialJson) }
    }
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) {
        this.text.append(text)
    }
    override suspend fun addRedactedThinking(data: String) = Unit
}

/** The upstream's whole round: the answer streamed as one text delta, its message done, and the response complete. */
private fun answered(text: String): List<JsonObject> {
    val message = buildJsonObject {
        put("type", "message")
        put("id", "msg_answer")
        put("role", "assistant")
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "output_text")
                        put("text", text)
                    },
                ),
            ),
        )
    }
    return listOf(
        buildJsonObject {
            put("type", "response.output_text.delta")
            put("output_index", 0)
            put("delta", text)
        },
        buildJsonObject {
            put("type", "response.output_item.done")
            put("output_index", 0)
            put("item", message)
        },
        buildJsonObject {
            put("type", "response.completed")
            putJsonObject("response") {
                put("status", "completed")
                put("output", JsonArray(listOf(message)))
            }
        },
    )
}

/** Claude Code's request for an answer in [schema], asked where [asked] says, beside an ordinary tool. */
private fun askedRequest(head: String, schema: JsonObject, asked: AskedIn): JsonObject = buildJsonObject {
    put("model", "$head--${STRICT_MODELS.getValue(head)}")
    put("max_tokens", 4096)
    put("stream", true)
    put("tools", askedTools(schema, asked))
    if (asked == AskedIn.FORCED_TOOL) {
        putJsonObject("tool_choice") {
            put("type", "tool")
            put("name", "StructuredOutput")
        }
    }
    if (asked == AskedIn.OUTPUT_CONFIG || asked == AskedIn.BOTH) {
        putJsonObject("output_config") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", schema)
            }
        }
    }
    put(
        "messages",
        JsonArray(
            listOf(
                buildJsonObject {
                    put("role", "user")
                    put("content", "Summarize the review in the requested shape.")
                },
            ),
        ),
    )
}

/** An ordinary tool, and the StructuredOutput tool over [schema] unless [asked] puts it only in output_config. */
private fun askedTools(schema: JsonObject, asked: AskedIn): JsonArray = JsonArray(
    buildList {
        add(
            buildJsonObject {
                put("name", "Bash")
                put("description", "Run a command")
                putJsonObject("input_schema") {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("command") { put("type", "string") } }
                    put("required", strings(listOf("command")))
                }
            },
        )
        if (asked != AskedIn.OUTPUT_CONFIG) {
            add(
                buildJsonObject {
                    put("name", "StructuredOutput")
                    put("description", "Return the final answer in the requested shape")
                    put("input_schema", schema)
                },
            )
        }
    },
)

/** claudex, an OpenAI API-key head and an xAI Responses head, built the way the daemon builds them from one topology. */
private class StrictHeads(root: Path, scope: CoroutineScope) {
    val providers: Map<String, Provider>

    init {
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val codexAuth = root.resolve("codex-auth.json")
        Files.writeString(codexAuth, """{"tokens":{"access_token":"synthetic-primary","account_id":"primary-id"}}""")
        val topology = TopologyLoader.parse(topology(codexAuth))
        val log = LogSink { }
        val assembly = ProviderAssembly(
            paths,
            scope,
            log = log,
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
            museArm = MuseResponsesArm(
                paths,
                log,
                scope,
                MuseKeyMintCall { _, _ -> MuseMintAttempt.Denied("no mint in this test") },
            ),
        )
        val config = ConfigService(paths, envReader = { null })
        providers = STRICT_MODELS.keys.associateWith { key ->
            val head = topology.heads.getValue(key)
            val provider = topology.providers.getValue(head.provider)
            assembly.buildProvider(
                ProviderBuild(
                    key = key,
                    head = head,
                    providerCfg = provider,
                    catalog = provider.catalogFor(head),
                    faultPlan = UpstreamFaultPlan(
                        watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                        loginCommand = "$key login",
                    ),
                    cfg = config.getConfig(key),
                ),
            ).provider
        }
    }

    fun close() {
        providers.values.forEach(Provider::onHeadStop)
    }

    private fun topology(codexAuth: Path): String = """
        [providers.codex]
        dialect = "openai-responses"
        base_url = "https://chatgpt.com/backend-api/codex"
        auth = { kind = "chatgpt-oauth", file = "${codexAuth.toString().replace("\\", "/")}" }

        [providers.codex.quirks]
        code_mode = false

        [[providers.codex.models]]
        id = "${STRICT_MODELS.getValue(ON_CODEX)}"
        context_window = 272000

        [providers.openai]
        dialect = "openai-responses"
        base_url = "https://api.openai.com/v1"
        auth = { kind = "api-key", env = "SPLICE_TEST_OPENAI_KEY" }

        [[providers.openai.models]]
        id = "${STRICT_MODELS.getValue(ON_OPENAI)}"
        context_window = 272000

        [providers.xai]
        dialect = "openai-responses"
        base_url = "https://api.x.ai/v1"
        auth = { kind = "api-key", env = "SPLICE_TEST_XAI_KEY" }

        [[providers.xai.models]]
        id = "${STRICT_MODELS.getValue(ON_XAI)}"
        context_window = 256000

        [heads.claude-codex]
        provider = "codex"
        port = 31521
        discovery_prefix = "claude-codex--"
        pinned_model = "${STRICT_MODELS.getValue(ON_CODEX)}"

        [heads.claude-openai]
        provider = "openai"
        port = 31522
        discovery_prefix = "claude-openai--"
        pinned_model = "${STRICT_MODELS.getValue(ON_OPENAI)}"

        [heads.claude-xai]
        provider = "xai"
        port = 31523
        discovery_prefix = "claude-xai--"
        pinned_model = "${STRICT_MODELS.getValue(ON_XAI)}"
    """.trimIndent()
}

class StructuredOutputEveryHeadTest {
    private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })

    /** Whether [head]'s vendor can hold every feature of [drawn] under strict, as its guide states it. */
    private fun holds(head: String, drawn: DrawnSchema): Boolean =
        if (head == ON_XAI) drawn.xaiHolds else drawn.openAiHolds

    /** Whether [schema] lies inside [head]'s vendor's strict subset. */
    private fun inside(head: String, schema: JsonObject): Boolean =
        if (head == ON_XAI) XaiStrictSubset.holds(schema) else OpenAiStrictSubset.holds(schema)

    /** The format [head] sends for [request], and the answer the client receives when the backend answers [answer]. */
    private suspend fun round(provider: Provider, request: JsonObject, answer: (JsonObject) -> String): Round {
        val body = AnthropicParse.parseAnthropicBody(request.toString())
        assertNull(provider.requestRefusal(body), "splice declines nothing here: $request")
        val built = provider.buildTurn(body, compact = false, sessionId = "structured")
        val format = built.requestBody.getValue("text").jsonObject.getValue("format").jsonObject
        val sink = AnswerSink()
        val sent = format.getValue("schema").jsonObject
        provider.streamTranslator(built.meta, signals).driveTurn(answered(answer(sent)).asFlow(), sink)
        val received = sink.toolInput["StructuredOutput"]?.toString() ?: sink.text.toString()
        return Round(format, received)
    }

    private data class Round(val format: JsonObject, val received: String)

    @Test
    fun `every head answers a requested schema, strict only inside its vendor's subset, in the schema asked for`(
        @TempDir tmp: Path,
    ) = runTest {
        val heads = StrictHeads(tmp, backgroundScope)
        try {
            val random = Random(1401)
            repeat(STRICT_RUNS) {
                val drawn = SchemaDraw(random).draw()
                val asked = AskedIn.entries[random.nextInt(AskedIn.entries.size)]
                heads.providers.forEach { (head, provider) ->
                    val request = askedRequest(head, drawn.schema, asked)
                    val round = round(provider, request) { sent -> SchemaAnswer(sent, random).of(sent).toString() }
                    val why = "$head, asked in $asked: ${round.format}"
                    val strict = round.format["strict"] == JsonPrimitive(true)
                    if (strict) {
                        assertTrue(inside(head, round.format.getValue("schema").jsonObject), "strict outside: $why")
                    } else {
                        assertEquals(JsonPrimitive(false), round.format["strict"], why)
                        assertEquals(drawn.schema, round.format["schema"], "not strict, so as written: $why")
                    }
                    // A native answer streams to the client as it comes, so only the tool's input can be brought back
                    // from a nullable field to an absent one.
                    val restorable = asked != AskedIn.OUTPUT_CONFIG || !drawn.optional || head == ON_XAI
                    if (holds(head, drawn) && restorable) assertTrue(strict, "the vendor can hold it, so strict: $why")
                    val received = Json.parseToJsonElement(round.received)
                    assertTrue(
                        SchemaCheck(drawn.schema).valid(received, drawn.schema),
                        "the client's answer $received breaks its schema: $why",
                    )
                }
            }
        } finally {
            heads.close()
        }
    }

    @Test
    fun `claudex answers the schema of the 14_01 refusal under strict, closed, and the client gets it as asked`(
        @TempDir tmp: Path,
    ) = runTest {
        // Its shape, with neutral names: required strings, an array of strings, an optional integer, and no
        // additionalProperties, which OpenAI's strict check demands on every object.
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                listOf("title", "summary", "verdict").forEach { name -> putJsonObject(name) { put("type", "string") } }
                putJsonObject("files") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("count") { put("type", "integer") }
            }
            put("required", strings(listOf("title", "summary", "verdict", "files")))
        }
        val heads = StrictHeads(tmp, backgroundScope)
        try {
            val answer = """{"title":"t","summary":"s","verdict":"ok","files":["a.kt"],"count":null}"""
            val round = round(
                heads.providers.getValue(ON_CODEX),
                askedRequest(ON_CODEX, schema, AskedIn.FORCED_TOOL),
            ) { answer }
            assertEquals(JsonPrimitive(true), round.format["strict"])
            val sent = round.format.getValue("schema").jsonObject
            assertTrue(OpenAiStrictSubset.holds(sent), "$sent")
            assertEquals(
                Json.parseToJsonElement("""{"title":"t","summary":"s","verdict":"ok","files":["a.kt"]}"""),
                Json.parseToJsonElement(round.received),
                "the count it left out reaches the client left out, not null",
            )
        } finally {
            heads.close()
        }
    }
}
