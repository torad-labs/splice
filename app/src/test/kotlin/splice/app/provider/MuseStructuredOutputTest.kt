// Goal: when Claude Code asks Muse for an answer in a JSON schema, through `output_config.format` or through its
// StructuredOutput tool, Muse accepts the request and its answer follows that schema, whatever the schema holds:
// nested objects, optional fields, arrays of objects. Anyone can tell by reading the request the Muse head would send:
// `text.format` carries the requested schema unchanged, and it never asks Muse to validate the schema as strict when
// the schema falls outside Meta's strict subset, which Muse refuses with a 400.
//
// Oct 27, 14:27 CT: Muse answered 400 "'additionalProperties' is required to be supplied and to be false." to 27
// claude-muse requests in two seconds, each with `text.format.strict: true` over a schema whose objects did not close.
// Meta's structured-output docs (https://dev.meta.ai/docs/structured-output) say output is always schema-constrained,
// that `strict: false` constrains it the same way, and that `strict: true` only adds a check of the schema against a
// subset modeled on OpenAI's: every object sets `additionalProperties: false` and lists every key in `required`.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
import splice.core.parse.AnthropicParse
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.topology.TopologyLoader
import splice.upstream.Provider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val SCHEMA_RUNS = 400
private const val MUSE_MODEL = "muse-wonder-full-1.0"

/** Where Claude Code puts the schema it wants the answer in. */
private enum class SchemaSource { OUTPUT_CONFIG, STRUCTURED_OUTPUT_TOOL, BOTH }

/** JSON schemas of the kinds callers write: objects nested a few levels, optional fields, arrays of objects and of
 *  scalars, enums and descriptions, and objects that leave `additionalProperties` out, open or closed. */
private class OutputSchemas(private val random: Random) {
    private var field = 0

    fun schema(): JsonObject = objectSchema(depth = 0)

    private fun objectSchema(depth: Int): JsonObject {
        val names = List(random.nextInt(1, 6)) { "field_${field++}" }
        val required = names.filter { random.nextInt(3) > 0 }
        return buildJsonObject {
            put("type", "object")
            if (random.nextInt(4) == 0) put("description", "an object at depth $depth")
            put("properties", JsonObject(names.associateWith { valueSchema(depth) }))
            if (required.isNotEmpty() || random.nextBoolean()) put("required", JsonArray(required.map(::JsonPrimitive)))
            when (random.nextInt(3)) {
                0 -> Unit
                1 -> put("additionalProperties", false)
                else -> put("additionalProperties", true)
            }
        }
    }

    private fun valueSchema(depth: Int): JsonObject = when (random.nextInt(if (depth < 3) 8 else 5)) {
        0 -> scalar("string")
        1 -> scalar("integer")
        2 -> scalar("boolean")
        3 -> buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("low", "medium", "high").map(::JsonPrimitive)))
        }
        4 -> buildJsonObject {
            put("type", "array")
            put("items", scalar("string"))
        }
        5, 6 -> buildJsonObject {
            put("type", "array")
            put("items", objectSchema(depth + 1))
        }
        else -> objectSchema(depth + 1)
    }

    private fun scalar(type: String): JsonObject = buildJsonObject { put("type", type) }
}

/** Claude Code's request for an answer in [schema], from [source], beside the tools it always offers. */
private fun structuredRequest(schema: JsonObject, source: SchemaSource): JsonObject = buildJsonObject {
    put("model", "claude-muse--$MUSE_MODEL")
    put("max_tokens", 2048)
    put("stream", true)
    val tools = buildList {
        add(
            buildJsonObject {
                put("name", "Bash")
                put("description", "Run a command")
                putJsonObject("input_schema") {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("command") { put("type", "string") } }
                    put("required", JsonArray(listOf(JsonPrimitive("command"))))
                }
            },
        )
        if (source != SchemaSource.OUTPUT_CONFIG) {
            add(
                buildJsonObject {
                    put("name", "StructuredOutput")
                    put("description", "Return the final answer in the requested shape")
                    put("input_schema", schema)
                },
            )
        }
    }
    put("tools", JsonArray(tools))
    if (source != SchemaSource.STRUCTURED_OUTPUT_TOOL) {
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

/** Meta's strict subset, as its docs state it: an object schema that sets `additionalProperties: false` and lists
 *  every property in `required`, at every level, array items included. */
private fun insideStrictSubset(schema: JsonElement?): Boolean {
    val node = schema as? JsonObject ?: return true
    val properties = node["properties"] as? JsonObject
    val closed = node["type"] != JsonPrimitive("object") || (
        node["additionalProperties"] == JsonPrimitive(false) &&
            (node["required"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }.toSet() ==
            properties.orEmpty().keys
        )
    return closed && properties.orEmpty().values.all(::insideStrictSubset) && insideStrictSubset(node["items"])
}

/** The Muse head, built the way the daemon builds it from a topology, over its minted-key sign-in. */
private class StructuredMuseHead(root: Path, scope: CoroutineScope) {
    val provider: Provider

    init {
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val museAuth = root.resolve("muse-auth.json")
        Files.writeString(museAuth, """{"access_token":"account-primary","api_key":"key-primary"}""")
        val topology = TopologyLoader.parse(
            """
            [providers.muse]
            dialect = "openai-responses"
            base_url = "https://api.meta.ai/v1"
            auth = { kind = "muse-oauth", file = "${museAuth.toString().replace("\\", "/")}" }

            [[providers.muse.models]]
            id = "$MUSE_MODEL"
            context_window = 1000000

            [heads.claude-muse]
            provider = "muse"
            port = 31511
            discovery_prefix = "claude-muse--"
            pinned_model = "$MUSE_MODEL"
            """.trimIndent(),
        )
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
        val head = topology.heads.getValue("claude-muse")
        val muse = topology.providers.getValue(head.provider)
        provider = assembly.buildProvider(
            ProviderBuild(
                key = "claude-muse",
                head = head,
                providerCfg = muse,
                catalog = muse.catalogFor(head),
                faultPlan = UpstreamFaultPlan(
                    watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                    loginCommand = "claude-muse login",
                ),
                cfg = ConfigService(paths, envReader = { null }).getConfig("claude-muse"),
            ),
        ).provider
    }
}

class MuseStructuredOutputTest {

    @Test
    fun `muse takes every requested schema as it was written and is never asked to check it as strict`(
        @TempDir tmp: Path,
    ) = runTest {
        val muse = StructuredMuseHead(tmp, backgroundScope)
        try {
            val random = Random(1427)
            repeat(SCHEMA_RUNS) {
                val schema = OutputSchemas(random).schema()
                val source = SchemaSource.entries[random.nextInt(SchemaSource.entries.size)]
                val body = AnthropicParse.parseAnthropicBody(structuredRequest(schema, source).toString())
                assertNull(muse.provider.requestRefusal(body), "splice declines nothing here: $source $schema")
                val request = muse.provider.buildTurn(body, compact = false, sessionId = "structured").requestBody
                val format = request.getValue("text").jsonObject.getValue("format").jsonObject
                assertEquals(JsonPrimitive("json_schema"), format["type"], "$source: $format")
                assertEquals(schema, format["schema"], "$source: Muse holds the answer to the schema as written")
                assertTrue(
                    format["strict"] != JsonPrimitive(true) || insideStrictSubset(schema),
                    "$source: Muse refuses strict over a schema outside its strict subset: $format",
                )
            }
        } finally {
            muse.provider.onHeadStop()
        }
    }

    @Test
    fun `the schema of the Oct 27 refusal reaches Muse as written`(@TempDir tmp: Path) = runTest {
        // Its shape, with neutral names: seven required fields of strings, an array of strings and an integer, and no
        // additionalProperties, which Muse's strict check demands on every object.
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                listOf("title", "summary", "verdict", "owner", "next").forEach { name ->
                    putJsonObject(name) { put("type", "string") }
                }
                putJsonObject("files") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("count") { put("type", "integer") }
            }
            put(
                "required",
                JsonArray(
                    listOf("title", "summary", "verdict", "owner", "next", "files", "count").map(::JsonPrimitive),
                ),
            )
        }
        val muse = StructuredMuseHead(tmp, backgroundScope)
        try {
            val body = AnthropicParse.parseAnthropicBody(
                structuredRequest(schema, SchemaSource.STRUCTURED_OUTPUT_TOOL).toString(),
            )
            val format = muse.provider.buildTurn(body, compact = false, sessionId = "structured").requestBody
                .getValue("text").jsonObject.getValue("format").jsonObject
            assertEquals(schema, format["schema"])
            assertTrue(
                format["strict"] != JsonPrimitive(true),
                "Muse answered 400 to this schema under strict: $format",
            )
        } finally {
            muse.provider.onHeadStop()
        }
    }
}
