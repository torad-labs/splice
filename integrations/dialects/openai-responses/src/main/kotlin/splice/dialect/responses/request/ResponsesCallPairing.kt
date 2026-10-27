// NEW: fix 1 of the Oct 27 Responses fixes (artifacts/responses-gaps-2026-10-27/goals.md). Every input splice sends a
// Responses backend answers each tool call once and carries each reasoning item once, as Codex's own history
// normalization leaves it: "every call (function/custom) has a corresponding output entry" and "every output has a
// corresponding call entry or names an external tool event" (codex-rs core/src/context_manager/history.rs, the
// normalize_history invariants, with ensure_call_outputs_present and remove_orphan_outputs).
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

private const val FIELD_TYPE = "type"
private const val FIELD_ID = "id"
private const val FIELD_CALL_ID = "call_id"
private const val TYPE_REASONING = "reasoning"
private const val TYPE_FUNCTION_OUTPUT = "function_call_output"
private const val TYPE_CUSTOM_OUTPUT = "custom_tool_call_output"
private const val TYPE_SEARCH_OUTPUT = "tool_search_output"
private const val SERVER_EXECUTION = "server"
private const val ABORTED = "aborted"

/** Codex's namespace for the ids of prompt-only outputs (normalize.rs SYNTHETIC_OUTPUT_ID_NAMESPACE). Changing it
 *  changes the ids the model reads, and with them the prompt cache. */
private const val SYNTHETIC_OUTPUT_NAMESPACE = "90d38d3e-6a5b-4d52-bfe2-2f1e634bfac4"
private const val UUID_BYTES = 16
private const val VERSION_BYTE = 6
private const val VARIANT_BYTE = 8
private const val VERSION_CLEAR = 0x0f
private const val VERSION_FIVE = 0x50
private const val VARIANT_CLEAR = 0x3f
private const val VARIANT_RFC = 0x80

/** The result kind that answers a call kind, and the prefix Codex gives the id of its stand-in. */
private data class Answer(val output: String, val prefix: String)

/** A call and its results meet on the result kind and the call id: a shell call is answered by a function output. */
private data class CallKey(val output: String, val callId: String)

/**
 * Keeps every tool call in a Responses input answered and every reasoning item single, the way Codex normalizes its
 * history before each request:
 * - a call goes out once, where it first appears;
 * - a call no result answers is answered right after it as Codex answers it: "aborted", or a completed client tool
 *   search that found nothing, with Codex's stable id when the call has one;
 * - a result whose call is not in the input is not sent, except a server-side tool search result, which Codex keeps
 *   too;
 * - a reasoning item goes out once, where its id first appears.
 * Every result a call was sent with goes out, in its place: a code-mode script's progress notification is a second
 * output of its call, and a client history that repeats a result repeats it. Everything else goes out as it came, and
 * an input with nothing to change is returned as it is.
 */
public class ResponsesCallPairing {
    private val answers = mapOf(
        "function_call" to Answer(TYPE_FUNCTION_OUTPUT, "fco"),
        "local_shell_call" to Answer(TYPE_FUNCTION_OUTPUT, "fco"),
        "custom_tool_call" to Answer(TYPE_CUSTOM_OUTPUT, "ctco"),
        "tool_search_call" to Answer(TYPE_SEARCH_OUTPUT, "tso"),
    )
    private val outputs = setOf(TYPE_FUNCTION_OUTPUT, TYPE_CUSTOM_OUTPUT, TYPE_SEARCH_OUTPUT)
    private val namespace: ByteArray = UUID.fromString(SYNTHETIC_OUTPUT_NAMESPACE).let {
        ByteBuffer.allocate(UUID_BYTES).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
    }

    /** [input] with each call once and answered, and each reasoning id once. */
    public fun pair(input: JsonArray): JsonArray {
        val plan = Plan(input)
        val paired = ArrayList<JsonElement>(input.size + plan.missing)
        var changed = plan.missing > 0
        input.forEachIndexed { at, item ->
            val kept = keep(plan, at, item as? JsonObject)
            if (kept) paired += item else changed = true
            plan.standInAfter(at)?.let { paired += it }
        }
        return if (changed) JsonArray(paired) else input
    }

    private fun keep(plan: Plan, at: Int, item: JsonObject?): Boolean {
        if (item == null) return true
        val type = JsonScalars.str(item, FIELD_TYPE)
        val call = callKey(item, type)
        val output = outputKey(item, type)
        return when {
            type == TYPE_REASONING -> firstReasoning(plan, item, at)
            call != null -> plan.callAt[call] == at
            output != null -> plan.callAt.containsKey(output)
            else -> true
        }
    }

    /** A reasoning item goes out where its id first appears; one without an id goes out as it came. */
    private fun firstReasoning(plan: Plan, item: JsonObject, at: Int): Boolean {
        val id = JsonScalars.str(item, FIELD_ID) ?: return true
        return plan.firstReasoning[id] == at
    }

    private fun callKey(item: JsonObject, type: String?): CallKey? {
        val answer = answers[type] ?: return null
        return JsonScalars.str(item, FIELD_CALL_ID)?.let { CallKey(answer.output, it) }
    }

    /** A result that answers some call: one with a call id, a server-side tool search aside. */
    private fun outputKey(item: JsonObject, type: String?): CallKey? = when {
        type !in outputs -> null
        type == TYPE_SEARCH_OUTPUT && JsonScalars.str(item, "execution") == SERVER_EXECUTION -> null
        else -> JsonScalars.str(item, FIELD_CALL_ID)?.let { CallKey(checkNotNull(type), it) }
    }

    /** Codex's stand-in for a call that never had a result (normalize.rs ensure_call_outputs_present). */
    private fun standIn(call: JsonObject, answer: Answer, callId: String): JsonObject = JsonObject(
        buildMap<String, JsonElement> {
            put(FIELD_TYPE, JsonPrimitive(answer.output))
            syntheticId(answer.prefix, JsonScalars.str(call, FIELD_ID))?.let { put(FIELD_ID, JsonPrimitive(it)) }
            put(FIELD_CALL_ID, JsonPrimitive(callId))
            if (answer.output == TYPE_SEARCH_OUTPUT) {
                put("status", JsonPrimitive("completed"))
                put("execution", JsonPrimitive("client"))
                put("tools", JsonArray(emptyList()))
            } else {
                put("output", JsonPrimitive(ABORTED))
            }
        },
    )

    /** Codex's synthetic_output_id: the prefix, an underscore and the version 5 UUID of "prefix:call item id" in its
     *  namespace, so a stand-in keeps one id across requests and the prompt cache holds. None for a call without an
     *  id, as Codex leaves older history. */
    private fun syntheticId(prefix: String, itemId: String?): String? {
        if (itemId.isNullOrEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(namespace)
        digest.update("$prefix:$itemId".toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        hash[VERSION_BYTE] = ((hash[VERSION_BYTE].toInt() and VERSION_CLEAR) or VERSION_FIVE).toByte()
        hash[VARIANT_BYTE] = ((hash[VARIANT_BYTE].toInt() and VARIANT_CLEAR) or VARIANT_RFC).toByte()
        val bits = ByteBuffer.wrap(hash, 0, UUID_BYTES)
        return "${prefix}_${UUID(bits.long, bits.long)}"
    }

    /** Where each call, result and reasoning item of one input sits, read in one pass before anything is written. */
    private inner class Plan(private val input: JsonArray) {
        val callAt = HashMap<CallKey, Int>()
        val firstReasoning = HashMap<String, Int>()
        private val answered = HashSet<CallKey>()

        /** The stand-ins this plan writes, which the input did not hold. */
        var missing = 0
            private set

        init {
            input.forEachIndexed { at, element ->
                (element as? JsonObject)?.let { place(it, JsonScalars.str(it, FIELD_TYPE), at) }
            }
            missing = callAt.keys.count { it !in answered }
        }

        private fun place(item: JsonObject, type: String?, at: Int) {
            if (type == TYPE_REASONING) {
                JsonScalars.str(item, FIELD_ID)?.let { firstReasoning.putIfAbsent(it, at) }
                return
            }
            callKey(item, type)?.let {
                callAt.putIfAbsent(it, at)
                return
            }
            outputKey(item, type)?.let(answered::add)
        }

        /** Codex's stand-in for the call at input position [at], when no result in the input answers it. */
        fun standInAfter(at: Int): JsonObject? {
            val call = input[at] as? JsonObject ?: return null
            val type = JsonScalars.str(call, FIELD_TYPE)
            val key = callKey(call, type)?.takeIf { callAt[it] == at && it !in answered } ?: return null
            return standIn(call, checkNotNull(answers[type]), key.callId)
        }
    }
}
