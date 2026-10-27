package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.security.MessageDigest
import java.util.Base64

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
    /** Why a round with a held chain sends its whole input with calls still open, or null when none are open. Code
     *  mode's own calls ([runtime]) stay open on an ordinary turn; any other [unanswered] call would be refused. */
    fun reason(runtime: List<String>, unanswered: List<String>, compaction: Boolean): String? = when {
        runtime.isNotEmpty() && !compaction ->
            "the server holds code mode's open call(s) (${runtime.joinToString(", ")}); code mode runs them " +
                "itself, so they are left open and the round sends its whole input"
        unanswered.isEmpty() -> null
        else ->
            "the server holds ${unanswered.size} unanswered tool call(s) this turn never answers " +
                "(${unanswered.joinToString(", ")}); a chained send would be refused"
    }

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
    /** [previous] holds the [digest] of each item the chain committed. */
    fun extendsPrefix(previous: List<String>, current: List<JsonObject>): Boolean =
        current.size >= previous.size &&
            previous.indices.all { previous[it] == digest(JsonWire.string(current[it])) }

    /** SHA-256 of an item's canonical text: what a chain keeps instead of the text. */
    fun digest(text: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

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
