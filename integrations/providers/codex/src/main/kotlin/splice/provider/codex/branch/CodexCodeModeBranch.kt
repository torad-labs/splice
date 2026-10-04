// NEW: V4-446 — exact-request retries and observable code-mode forks, apart from ordinary turns.
package splice.provider.codex.branch

import splice.core.turn.CodeModeDivergenceMarker
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeDriver
import splice.provider.codex.CodexCodeModeMachine
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeValidation
import splice.upstream.codemode.CodeModeResult
import splice.upstream.transport.StreamTornBeforeClient
import java.io.IOException

/** A byte-identical input replays its issued callback; a changed accepted result never feeds
 * the other branch’s running cell. Both decisions are keyed to the canonical request digest. */
internal class CodexCodeModeBranch(
    private val registry: CodexCodeModeRegistry,
    private val driver: CodexCodeModeDriver,
    private val machine: CodexCodeModeMachine,
    private val validation: CodexCodeModeValidation,
    private val log: LogSink,
) {
    /** Before per-conversation retention trims a completed record, the same input owns its step. */
    suspend fun replay(context: CodeModeRunContext): TurnOutcome? {
        val calls = registry.recordsFor(context.key).firstNotNullOfOrNull { record ->
            record.issued.firstOrNull { it.requestDigest == context.digest && !record.abandoned() }?.calls
        }
        return calls?.let { machine.replay(it, context.sink) }
    }

    /** Exclude an incompatible A record from rewriting and ownership, but keep B's own records. */
    fun conflictingRecords(context: CodeModeRunContext): Set<String> {
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        return registry.recordsFor(context.key).filter { record ->
            record.results.keys.any(resultIds::contains) && validation.conflicts(record, context.turn)
        }.mapTo(mutableSetOf()) { it.id }
    }

    /** No owned B callback remains: send B's already-canonicalized history upstream without
     * rewriting its changed results through A's completed record. */
    suspend fun sendOwnHistory(context: CodeModeRunContext, ownHistory: CodeModeBody): TurnOutcome {
        log("[code-mode] observable-divergence: accepted callback changed; sending this history upstream")
        return try {
            mark(driver.drive(context, null, ownHistory, context.post(ownHistory)))
        } catch (error: IOException) {
            error.addSuppressed(CodeModeDivergenceMarker())
            throw error
        } catch (error: StreamTornBeforeClient) {
            error.addSuppressed(CodeModeDivergenceMarker())
            throw error
        }
    }

    /** Outcome usage reaches both the perf row and the trace, including a failed upstream post. */
    private fun mark(outcome: TurnOutcome): TurnOutcome = when (outcome) {
        is TurnOutcome.Success -> outcome.copy(usage = outcome.usage.copy(codeModeDiverged = true))
        is TurnOutcome.Failure -> outcome.copy(salvagedUsage = outcome.salvagedUsage.copy(codeModeDiverged = true))
        is TurnOutcome.ClientAbandoned -> outcome.copy(
            salvagedUsage = outcome.salvagedUsage.copy(codeModeDiverged = true),
        )
    }
}
