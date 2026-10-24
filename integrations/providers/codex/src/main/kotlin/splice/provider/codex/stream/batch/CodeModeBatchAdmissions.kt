package splice.provider.codex.stream.batch

import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.state.CodeModeWeight
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.provider.codex.stream.source.CodeModeItemSeal

/** Every queued item has a durable unstarted proof before its source cursor can become executable. */
internal class CodeModeBatchAdmissions(
    private val registry: CodexCodeModeRegistry,
    admission: CodeModeStreamAdmission,
) : CodeModeStreamAdmission, CodeModeItemSeal {
    private var admission: CodeModeStreamAdmission? = admission
    private val publication = CodeModeBatchPublication(registry)
    private val records = mutableListOf<CodeModeRecord>()
    private val started = mutableListOf<CodeModeRecord>()
    private var captured: List<JsonObject> = emptyList()

    override fun admit(call: GatewayCustomCall): CodeModeRecord {
        check(started.none { it.origin.outerCallId == call.callId }) { "duplicate code-mode item identity" }
        return checkNotNull(admission) { "response admission already ended" }.admit(call).also { started += it }
    }

    override fun seal(
        record: CodeModeRecord,
        call: GatewayCustomCall,
        continuity: CodeModeContinuity?,
        snapshot: TurnOutcome.Success?,
    ): Boolean {
        check(record !in records) { "duplicate completed code-mode item" }
        val next = records + record
        val items = snapshot?.let(::modelItems) ?: (captured + call.raw)
        check(items.take(captured.size) == captured) { "model response batch changed its completed prefix" }
        publication.publish(next, record, call, continuity, items)
        records += record
        captured = items
        CodeModeBatchLinks.link(records)
        return true
    }

    fun release() {
        admission = null
        started.clear()
        records.clear()
    }

    fun capture(outcome: TurnOutcome.Success) {
        val items = modelItems(outcome)
        check(items.take(captured.size) == captured) { "model response batch changed its completed prefix" }
        captured = items
        val owner = records.firstOrNull() ?: return
        val before = owner.nativeBatch?.items.orEmpty()
        if (items == before) return
        check(items.take(before.size) == before) { "model response batch changed its completed prefix" }
        val growth = items.drop(before.size).sumOf(CodeModeWeight.STORED::json)
        registry.changes.save(owner, growthBytes = growth) {
            it.nativeBatch = checkNotNull(it.nativeBatch).copy(items = items)
        }
    }

    private fun modelItems(outcome: TurnOutcome.Success): List<JsonObject> {
        outcome.handoffs.nativeOutput?.let { return it }
        val calls = outcome.handoffs.customCalls
        val first = calls.firstOrNull() ?: return emptyList()
        val after = outcome.handoffs.nativeAfterExec.orEmpty().filterIsInstance<JsonObject>()
        val known = after.mapNotNull { item -> JsonScalars.str(item, "call_id")?.takeIf(String::isNotBlank) }.toSet()
        return outcome.handoffs.nativePrefix.orEmpty() + first.raw + after +
            calls.drop(1).filter { it.callId !in known }.map(GatewayCustomCall::raw)
    }
}
