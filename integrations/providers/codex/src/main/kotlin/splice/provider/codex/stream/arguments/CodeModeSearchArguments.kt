package splice.provider.codex.stream.arguments

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.upstream.codemode.CodeModeText
import splice.upstream.codemode.CodeModeToolSearch
import splice.upstream.codemode.json.CodeModeArgumentJson
import java.math.BigInteger

/** SearchToolCallParams is decoded before the handler rejects blank queries or a zero result limit. */
internal object CodeModeSearchArguments {
    fun read(source: String): JsonObject {
        val values = CodeModeStructArguments(
            "SearchToolCallParams",
            listOf(SEARCH_QUERY, SEARCH_LIMIT),
            SEARCH_QUERY,
        ).read(source, ::field)
        return buildJsonObject {
            put(SEARCH_QUERY, checkNotNull(values[SEARCH_QUERY] as? String))
            put(
                SEARCH_LIMIT,
                (values[SEARCH_LIMIT] as? BigInteger)?.let(::JsonPrimitive) ?: JsonNull,
            )
        }
    }

    fun validate(arguments: JsonObject): JsonObject = read(JsonWire.string(CodeModeText.jsonValue(arguments)))

    fun observation(source: String): JsonObject {
        val value = read(source)
        val query = CodeModeText.trim(checkNotNull(JsonScalars.str(value, SEARCH_QUERY)))
        require(query.isNotEmpty()) { "query must not be empty" }
        val limit = JsonScalars.str(value, SEARCH_LIMIT)?.toBigInteger()
            ?: BigInteger.valueOf(CodeModeToolSearch.DEFAULT_LIMIT.toLong())
        require(limit.signum() != 0) { "limit must be greater than zero" }
        return buildJsonObject {
            put(SEARCH_QUERY, query); put(SEARCH_LIMIT, limit.toString())
        }
    }

    private fun field(reader: CodeModeArgumentJson, field: String): Any? = if (field == SEARCH_QUERY) {
        val value = reader.value("a string")
        val string = (value as? JsonPrimitive)?.takeIf { it.isString }
        string?.let(JsonScalars::str) ?: reader.invalid(value, "a string")
    } else {
        val value = reader.value("usize")
        if (value == JsonNull) null else reader.unsigned(value, "usize")
    }
}

private const val SEARCH_QUERY = "query"
private const val SEARCH_LIMIT = "limit"
