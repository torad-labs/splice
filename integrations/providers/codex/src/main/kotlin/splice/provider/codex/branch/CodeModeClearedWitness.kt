package splice.provider.codex.branch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeClientAcknowledgement
import java.security.MessageDigest
import java.util.HexFormat

/** Resolves only the durable pre-script post that owns an execution record. */
internal fun interface CodeModePostedClientStart {
    operator fun invoke(record: CodeModeRecord): CodeModeClientAcknowledgement?
}

/** Request-local proof. Only existing accepted bytes normalize the hash, never the outgoing request. */
internal class CodeModeClearedWitness(
    private val context: CodeModeRunContext,
    private val records: List<CodeModeRecord>,
    log: LogSink,
) {
    private val rendering = context.turn.rendering
    private val input = context.client?.request?.second.orEmpty()
    private val codec = CodexCodeModeHistoryCodec(Json)
    private val items = input.dropWhile(codec::isPreamble)
    private val baseline = CodeModeClearedBaseline(codec, items)
    private val identity = CodeModeClearedIdentity(context, items, log)
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

    fun verify() = verify { null }

    fun verify(start: CodeModePostedClientStart) {
        if (rendering.clearedCallbacks.isEmpty()) return
        checkpoint()
        items.forEach { element ->
            val item = element as? JsonObject
            val id = item?.let { text(it, "call_id") }.orEmpty()
            when (item?.let { text(it, "type") }) {
                "function_call" -> authorize(id, start)
                "function_call_output" -> Unit
                else -> Unit
            }
            val replacement = normalized(item, id)
            if (replacement == null) {
                feed(element)
            } else {
                feedHex(replacement.first)
                replacement.second.forEach(::feed)
            }
        }
    }

    private fun authorize(id: String, start: CodeModePostedClientStart) {
        val owner = owners[id]?.singleOrNull() ?: return
        val cleared = rendering.verifiedCleared.values.flatten().toSet()
        val posted = if (owner.replayAnchors?.client?.acknowledgement == null &&
            owner.accepted.clientOutputDigest(id) != null
        ) {
            start(owner)
        } else {
            null
        }
        val continued = owner.id in verified || baseline.matchesAcknowledgedStart(owner, id, cleared, posted)
        if (!continued || owner.sessionId != context.turn.sessionId) return
        if (identity.matches(owner, id)) authorized[id] = owner
    }

    private fun normalized(item: JsonObject?, id: String): Pair<String, List<JsonElement>>? {
        if (!clearedOutput(item)) return null
        val owner = authorized[id] ?: return null
        return owner.accepted.media(id)?.let { media ->
            val hash = CodeModeClientOutputDigests.replay(
                owner.accepted.clientOutputDigest(id),
                checkNotNull(item),
                owner.results.getValue(id).output,
            )
            hash?.let {
                rendering.verifiedCleared.getOrPut(owner.id) { mutableSetOf() }.add(id)
                it to media
            }
        }
    }

    private fun clearedOutput(item: JsonObject?): Boolean = item?.let {
        text(it, "type") == "function_call_output" && text(it, "output") == "[Old tool result content cleared]"
    } == true

    private fun feed(item: JsonElement) = feedHex(InputDigest.hex(item))

    private fun feedHex(hex: String) {
        digest.update(hex.toByteArray(Charsets.UTF_8))
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
