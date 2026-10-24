package splice.provider.codex.branch

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.upstream.codemode.CodeModeCall
import java.security.MessageDigest
import java.util.HexFormat

/** Request-local proof. Only existing accepted bytes normalize the hash, never the outgoing request. */
internal class CodeModeClearedWitness(
    private val context: CodeModeRunContext,
    private val records: List<CodeModeRecord>,
    private val log: LogSink,
) {
    private val rendering = context.turn.rendering
    private val input = context.client?.request?.second.orEmpty()
    private val codec = CodexCodeModeHistoryCodec(Json)
    private val items = input.dropWhile(codec::isPreamble)
    private val calls = items.filterIsInstance<JsonObject>().filter { text(it, "type") == "function_call" }
        .groupBy { text(it, "call_id") }
    private val outputs = items.filterIsInstance<JsonObject>().filter { text(it, "type") == "function_call_output" }
        .groupBy { text(it, "call_id") }
    private val owners = records.flatMap { record ->
        record.clientIds().map {
            it to record
        }
    }.groupBy({ it.first }, { it.second })
    private val checkpoints = records.groupBy { it.replayAnchors?.client?.count }
    private val verified = mutableSetOf<String>()
    private val authorized = mutableMapOf<String, CodeModeRecord>()
    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0

    fun verify() {
        rendering.verifiedCleared.clear()
        if (rendering.clearedCallbacks.isEmpty()) return
        checkpoint()
        items.forEach { element ->
            val item = element as? JsonObject
            val id = item?.let { text(it, "call_id") }.orEmpty()
            when (item?.let { text(it, "type") }) {
                "function_call" -> authorize(id)
                "function_call_output" -> Unit
                else -> Unit
            }
            val replacement = normalized(item, id)
            (replacement ?: listOf(element)).forEach(::feed)
        }
    }

    private fun authorize(id: String) {
        val owner = owners[id]?.singleOrNull() ?: return
        if (owner.id !in verified || owner.sessionId != context.turn.sessionId) return
        if (matches(owner, id)) authorized[id] = owner
    }

    private fun matches(owner: CodeModeRecord, id: String): Boolean {
        val candidate = rendering.clearedCallbacks[id] ?: return false
        val call = owner.issued.flatMap { it.calls }
            .filter { it.clientId == id }.distinct().singleOrNull() ?: return false
        val identity = matchesCall(call, candidate) && uniqueWire(id, candidate)
        val result = context.turn.toolResults.singleOrNull { it.id == id }
        return identity && result?.isError == owner.results[id]?.isError
    }

    private fun matchesCall(call: CodeModePending, candidate: CodeModeCall): Boolean =
        call.exposed && call.name == candidate.name && call.arguments == candidate.arguments

    private fun uniqueWire(id: String, candidate: CodeModeCall): Boolean {
        val wire = calls[id]?.singleOrNull() ?: return false
        return outputs[id]?.size == 1 && matchesWire(wire, candidate.name, candidate.arguments)
    }

    private fun matchesWire(item: JsonObject, name: String, arguments: JsonObject): Boolean {
        val parsed = try {
            Json.parseToJsonElement(text(item, "arguments"))
        } catch (_: SerializationException) {
            log("[code-mode] cleared callback replay skipped: malformed callback arguments")
            return false
        }
        return text(item, "name") == name && parsed == arguments
    }

    private fun normalized(item: JsonObject?, id: String): List<JsonElement>? {
        if (!clearedOutput(item)) return null
        val owner = authorized[id] ?: return null
        return owner.accepted.media(id)?.let { media ->
            rendering.verifiedCleared.getOrPut(owner.id) { mutableSetOf() }.add(id)
            val original =
                JsonObject(checkNotNull(item) + ("output" to JsonPrimitive(owner.results.getValue(id).output)))
            listOf(original) + media
        }
    }

    private fun clearedOutput(item: JsonObject?): Boolean = item?.let {
        text(it, "type") == "function_call_output" && text(it, "output") == "[Old tool result content cleared]"
    } == true

    private fun feed(item: JsonElement) {
        digest.update(InputDigest.hex(item).toByteArray(Charsets.UTF_8))
        count++
        checkpoint()
    }

    private fun checkpoint() {
        val pending = checkpoints[count].orEmpty()
        if (pending.isEmpty()) return
        val copy = digest.clone() as? MessageDigest ?: error("SHA-256 prefix snapshots are unavailable")
        val hash = HexFormat.of().formatHex(copy.digest())
        pending.filter { it.replayAnchors?.client?.digest == hash }.forEach { verified += it.id }
    }

    private fun text(item: JsonObject, key: String): String = JsonScalars.str(item, key).orEmpty()
}
