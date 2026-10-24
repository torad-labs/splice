package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

/** Immutable facts needed to explain a full send, without exposing request content. */
internal data class WsSendExpectation(
    val input: List<String>,
    val generation: Long,
    val properties: String,
    val pendingCalls: Set<String>,
)

internal fun interface WsSuffixCheck {
    operator fun invoke(items: List<JsonObject>): List<JsonObject>?
}

/** Diagnostics share the prefix and answer checks used by the actual delta classifier. */
internal object WsFullSendDiagnostic {
    fun code(
        expected: WsSendExpectation,
        request: JsonObject,
        generation: Long,
        properties: String,
        suffix: WsSuffixCheck,
    ): String = when {
        expected.generation != generation -> "connection_generation_changed"
        expected.properties != properties -> "request_properties_changed"
        else -> inputCode(expected, request[FIELD_INPUT] as? JsonArray, suffix)
    }

    private fun inputCode(
        expected: WsSendExpectation,
        input: JsonArray?,
        suffix: WsSuffixCheck,
    ): String {
        val items = input?.filterIsInstance<JsonObject>().orEmpty()
        return when {
            input == null -> "input_missing_or_not_array"
            items.size != input.size -> "input_contains_non_object"
            !WsChainingChecks.extendsPrefix(expected.input, items) -> "input_prefix_changed"
            items.size == expected.input.size -> "no_new_input"
            else -> suffixCode(expected.pendingCalls, suffix(items))
        }
    }

    private fun suffixCode(pendingCalls: Set<String>, delta: List<JsonObject>?): String = when {
        delta == null -> "unverified_suffix_or_server_echo"
        delta.isEmpty() -> "suffix_has_no_client_input"
        WsChainingChecks.unansweredCalls(pendingCalls, delta).isNotEmpty() -> "unanswered_tool_call"
        else -> "delta_unavailable"
    }
}

internal object WsChainingChecks {
    fun extendsPrefix(previous: List<String>, current: List<JsonObject>): Boolean =
        current.size >= previous.size &&
            previous.indices.all { previous[it] == JsonWire.string(current[it]) }

    /** Echoing a call is not answering it; only a same-ID non-call item settles the held call. */
    fun unansweredCalls(pendingCalls: Set<String>, items: List<JsonObject>): List<String> =
        pendingCalls.filterNot { id ->
            items.any {
                JsonScalars.str(it[FIELD_CALL_ID]) == id &&
                    !JsonScalars.strOrEmpty(it[WS_FIELD_TYPE]).endsWith(CALL_SUFFIX)
            }
        }
}

private const val CALL_SUFFIX = "_call"
