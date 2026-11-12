package splice.provider.codex.stream.batch

import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeValidation
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.history.CodeModeUpstreamHistory
import splice.provider.codex.stream.CodeModeRecordFactory
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.provider.codex.stream.source.CodeModeItemPrefix

/** Terminal-only transports still retain one complete response and independent unstarted item identities. */
internal class CodeModeBatchPreparation(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val sent: CodeModeUpstreamHistory,
) {
    fun prepare(context: CodeModeRunContext, body: CodeModeBody, outcome: TurnOutcome.Success): CodeModeRecord {
        val validation = CodexCodeModeValidation(config)
        val calls = outcome.handoffs.customCalls
        check(
            calls.map(GatewayCustomCall::callId).distinct().size == calls.size,
        ) { "duplicate code-mode item identity" }
        calls.forEach { call -> check(validation.outer(call) == null) { "invalid completed code-mode item" } }
        val factory = CodeModeRecordFactory(config, wire)
        val records = mutableListOf<CodeModeRecord>()
        val admissions = CodeModeBatchAdmissions(
            registry,
            CodeModeStreamAdmission { call ->
                check(
                    config.bounds.maxRounds?.let { context.scripts < it } != false,
                ) { "code-mode round limit exceeded" }
                check(
                    context.completed.none { it.origin.outerCallId == call.callId },
                ) { "duplicate completed item identity" }
                context.scripts++
                val item = checkNotNull(CodeModeItemPrefix.of(outcome, call))
                val record = checkNotNull(factory.create(context, call, body, item, sent.clientStart(context)))
                record.phase = CodeModePhase.STARTING
                record.sourceState = CodeModeSourceState(itemComplete = false)
                record.postedOn = sent.newest(context)
                check(registry.add(record)) { "code-mode registry capacity reached" }
                records += record
                record
            },
        )
        calls.forEach { call ->
            val record = admissions.admit(call)
            val snapshot = checkNotNull(CodeModeItemPrefix.of(outcome, call))
            admissions.seal(record, call, wire.continuity(snapshot), snapshot)
        }
        admissions.capture(outcome)
        val owner = records.first()
        registry.changes.save(owner, wholeKey = true) {
            records.forEach { it.sourceState = checkNotNull(it.sourceState).copy(complete = true, consumed = true) }
        }
        admissions.release()
        return owner
    }
}
