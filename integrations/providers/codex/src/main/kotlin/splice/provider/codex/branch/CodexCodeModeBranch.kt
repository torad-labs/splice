// NEW: V4-446 — exact-request retries and observable code-mode forks, apart from ordinary turns.
package splice.provider.codex.branch

import kotlinx.serialization.json.JsonObject
import splice.core.turn.CodeModeDivergenceMarker
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeRewrite
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeDriver
import splice.provider.codex.CodexCodeModeMachine
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeValidation
import splice.provider.codex.stream.CodeModeEndings
import splice.upstream.codemode.CodeModeResult
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
        return calls?.let { machine.replay(it, context.link.sink) }
    }

    /** Exclude an incompatible A record from rewriting and ownership, but keep B's own records. */
    fun conflictingRecords(context: CodeModeRunContext): Set<String> {
        val records = registry.recordsFor(context.key)
        if (context.turn.rendering.clearedCallbacks.isNotEmpty()) {
            CodeModeClearedWitness(context, records, log).verify { driver.sent.postedClientStart(context, it) }
        }
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        val duplicates = duplicateCallbacks(context)
        return records.filter { record ->
            val ambiguous = record.holdsAnyClientId(duplicates)
            val changed = record.results.keys.any(resultIds::contains) && validation.conflicts(record, context.turn)
            ambiguous || changed
        }.mapTo(mutableSetOf()) { it.id }
    }

    private fun duplicateCallbacks(context: CodeModeRunContext): Set<String> =
        context.client?.request?.second.orEmpty().filterIsInstance<JsonObject>()
            .filter { JsonScalars.str(it, "type") in setOf("function_call", "function_call_output") }
            .groupingBy { JsonScalars.str(it, "type") to JsonScalars.str(it, "call_id") }.eachCount()
            .filterValues { it > 1 }.keys.mapNotNull { it.second }.toSet()

    /** Select the accepted owner before rendering ordinary history, using the same fork exclusions. */
    suspend fun canonicalize(
        context: CodeModeRunContext,
        body: CodeModeBody,
        callbackIds: Set<String>,
    ): CodeModeRewrite {
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        val capture = registry.owner(context.key, context.digest, resultIds, callbackIds, context.conflicts)
            ?: context.completed.lastOrNull()
        return context.link.post.canonicalize(body, context.completed, context.turn.rendering.replayMedia, capture)
    }

    /** No owned B callback remains: send B's already-canonicalized history upstream without
     * rewriting its changed results through A's completed record. */
    suspend fun sendOwnHistory(context: CodeModeRunContext, ownHistory: CodeModeBody): TurnOutcome {
        log("[code-mode] observable-divergence: accepted callback changed; sending this history upstream")
        return try {
            mark(driver.drive(context, null, ownHistory, context.link.post(ownHistory))).also {
                // A tear the upstream ended this history on is the divergence's too (TurnConnEnd counts it).
                CodeModeEndings.tornCause()?.addSuppressed(CodeModeDivergenceMarker())
            }
        } catch (error: IOException) {
            error.addSuppressed(CodeModeDivergenceMarker())
            throw error
        }
    }

    /** Outcome usage reaches both the perf row and the trace, including a failed upstream post. */
    private fun diverged(usage: Usage): Usage = usage.copy(origin = usage.origin.copy(codeModeDiverged = true))

    private fun mark(outcome: TurnOutcome): TurnOutcome = when (outcome) {
        is TurnOutcome.Success -> outcome.copy(usage = diverged(outcome.usage))
        is TurnOutcome.Failure -> outcome.copy(salvagedUsage = diverged(outcome.salvagedUsage))
        is TurnOutcome.ClientAbandoned -> outcome.copy(
            salvagedUsage = diverged(outcome.salvagedUsage),
        )
    }
}
