package splice.upstream.codemode.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeRankedSearch
import splice.upstream.codemode.CodeModeSchemaProjection
import splice.upstream.codemode.CodeModeSearchTokenizer
import splice.upstream.codemode.json.CodeModeJson
import splice.upstream.codemode.json.CodeModeJsonException
import splice.upstream.codemode.search.CodeModeBm25
import splice.upstream.codemode.search.CodeModeSearchCorpus
import splice.upstream.codemode.search.CodeModeSearchEntry
import java.util.HexFormat

/** Side-effect-free access to the production Code Mode core for a source-derived parity fixture. */
public object CodeModeCoreProbe {
    public fun evaluate(input: JsonObject): JsonObject {
        val operation = JsonScalars.str(input, "operation")
        return when (operation) {
            "describe", "prepare", "augment", "collect" -> CodeModeProbeDefinitions.evaluate(input, operation)
            "search", "rank", "tokenize" -> discovery(input, operation)
            else -> output(input, operation)
        }
    }

    private fun discovery(input: JsonObject, operation: String): JsonObject = when (operation) {
        "search" -> search(input)
        "rank" -> rank(input)
        "tokenize" -> buildJsonObject {
            put(
                "tokens",
                JsonArray(
                    CodeModeSearchTokenizer.tokens(JsonScalars.str(input, "text").orEmpty())
                        .map { kotlinx.serialization.json.JsonPrimitive(it) },
                ),
            )
        }
        else -> error("unknown code-mode discovery operation")
    }

    private fun output(input: JsonObject, operation: String?): JsonObject = when (operation) {
        "format" -> CodeModeProbeFormats.evaluate(input)
        "tool_output" -> CodeModeProbeOutputs.evaluate(input)
        "prefixes" -> CodeModeProbePrefixes.evaluate(input)
        "json_value" -> jsonValue(input)
        "schema_projection" -> buildJsonObject {
            put(
                "schema",
                input["schema"]?.let(CodeModeSchemaProjection::canonical) ?: kotlinx.serialization.json.JsonNull,
            )
        }
        else -> throw IllegalArgumentException("unknown code-mode core probe operation")
    }

    private fun jsonValue(input: JsonObject): JsonObject = try {
        val hex = JsonScalars.str(input, "bytes_hex")
        val value = if (hex == null) {
            CodeModeJson.parse(requireNotNull(JsonScalars.str(input, "source")))
        } else {
            CodeModeJson.parse(HexFormat.of().parseHex(hex))
        }
        buildJsonObject { put("value", value) }
    } catch (error: CodeModeJsonException) {
        buildJsonObject { put("error", error.diagnostic) }
    }

    private fun rank(input: JsonObject): JsonObject {
        val corpus = (input["corpus"] as? JsonArray).orEmpty().map { JsonScalars.str(it).orEmpty() }
        val matches = CodeModeBm25(corpus).matches(JsonScalars.str(input, "query").orEmpty())
        return buildJsonObject {
            put(
                "matches",
                JsonArray(
                    matches.map { match ->
                        buildJsonObject {
                            put("index", match.index)
                            put("score", match.score)
                        }
                    },
                ),
            )
        }
    }

    private fun search(input: JsonObject): JsonObject {
        val tools = CodeModeProbeTools.loadable(input["tools"])
        val entries = (input["entries"] as? JsonArray)?.map { entry ->
            val item = entry as JsonObject
            val members = CodeModeProbeTools.loadable(item["tools"])
            CodeModeSearchEntry(members, JsonScalars.str(item, "search_text") ?: CodeModeSearchCorpus.text(members))
        } ?: CodeModeSearchCorpus.entries(tools)
        val names = (input["allowed_names"] as? JsonArray)?.mapNotNull { JsonScalars.str(it) }?.toSet()
            ?: entries.flatMap { it.tools }.map { it.codeModeName }.toSet()
        val search =
            CodeModeRankedSearch(entries, names, CodeModeProbeTools.flag(input, "tool_description_first", false))
        return try {
            search.search((input["arguments"] as? JsonObject) ?: JsonObject(emptyMap()))
        } catch (error: IllegalArgumentException) {
            buildJsonObject { put("error", error.message.orEmpty()) }
        }
    }
}
