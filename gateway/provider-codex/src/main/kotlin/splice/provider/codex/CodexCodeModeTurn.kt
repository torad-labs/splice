// NEW: serializes each conversation's code-mode replay, resume, and fresh-turn decisions.
package splice.provider.codex

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.turn.ErrorType
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.spi.CodeModeResult
import splice.spi.InterceptedRoundPost
import splice.spi.WireSink
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val CODE_MODE_LOCK_STRIPES: Int = 64
private const val RECORD_ID_LOG_CHARS: Int = 8

internal data class CodeModeRunInput(
    val turn: CodexCodeModeBridge.Turn,
    val initialOuter: GatewayCustomCall?,
    val disableParallel: Boolean,
    val bodyJson: String,
    val sink: WireSink,
    val post: InterceptedRoundPost,
)

internal data class CodeModeRunContext(
    val turn: CodexCodeModeBridge.Turn,
    val disableParallel: Boolean,
    val key: String,
    val digest: String,
    val sink: WireSink,
    val post: InterceptedRoundPost,
)

/**
 * History that no longer lines up with a record is DEGRADED, never refused. The status quo before
 * code mode was "send the history the client sent", and the client's history is always a valid one:
 * the owned callbacks are real tool calls with real outputs. A refusal instead turns any false
 * positive (a grown tool list, a model switch, a record past its TTL) into a dead conversation with
 * no way out but compaction — which is what happened live on 2026-09-07.
 */
internal class CodexCodeModeTurn(
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val driver: CodexCodeModeDriver,
    private val resume: CodexCodeModeResume,
    private val log: LogSink,
) {
    private val identity = CodexCodeModeIdentity()
    private val locks = List(CODE_MODE_LOCK_STRIPES) { Mutex() }

    /** Conversation-scoped notes already logged — one line per conversation, not one per turn. */
    private val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun run(input: CodeModeRunInput): TurnOutcome {
        val key = identity.turnKey(input.turn)
        val context = CodeModeRunContext(
            input.turn,
            input.disableParallel,
            key,
            identity.digest(input.bodyJson),
            input.sink,
            input.post,
        )
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            try {
                registry.save(retryOnly = true)
                runLocked(context, input.initialOuter, input.bodyJson)
            } catch (error: CodeModePersistenceException) {
                error.outcome()
            }
        }
    }

    private suspend fun runLocked(
        context: CodeModeRunContext,
        initialOuter: GatewayCustomCall?,
        bodyJson: String,
    ): TurnOutcome {
        historyNotes(context.turn, context.key, context.digest)
            .filter { announced.add("${context.key}|$it") }
            .forEach { log("[code-mode] $it") }
        val completed = registry.completed(context.key)
        val completedHistory = wire.canonicalize(bodyJson, completed)
        completedHistory.error?.let { return failure(it) }
        val canonicalBody = checkNotNull(completedHistory.bodyJson)
        val owner = placedOwner(context, canonicalBody)
        return when {
            owner != null -> resumeOwner(owner, context)
            completed.any { it.lastDigest == context.digest } ->
                driver.drive(context, null, canonicalBody, context.post(canonicalBody))
            else -> driver.drive(context, initialOuter, canonicalBody, context.post(canonicalBody))
        }
    }

    /** The active or lost owner whose baseline still places in this history, with that history
     *  restored around it — or null when there is none, or when the one there was is abandoned. */
    private fun placedOwner(context: CodeModeRunContext, canonicalBody: String): PlacedOwner? {
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        val owner = registry.owner(context.key, context.digest, resultIds) ?: return null
        val restored = wire.restoreBaseline(canonicalBody, owner)
        val error = restored.error ?: return PlacedOwner(owner, checkNotNull(restored.bodyJson))
        abandon(owner, error)
        return null
    }

    private suspend fun resumeOwner(placed: PlacedOwner, context: CodeModeRunContext): TurnOutcome =
        if (placed.record.phase == CodeModePhase.ACTIVE) {
            resume.active(placed.record, context, placed.bodyJson)
        } else {
            resume.lost(placed.record, context, placed.bodyJson)
        }

    /** A running script whose history moved underneath it is abandoned: cell closed, evidence kept
     *  on the LOST record, and the turn continues upstream on the client's own history. */
    private fun abandon(owner: CodeModeRecord, error: String) {
        registry.lose(owner, "code-mode history no longer places the running script: $error; source was not rerun")
        log(
            "[code-mode] abandoned record ${owner.id.take(RECORD_ID_LOG_CHARS)} (outer ${owner.outerCallId}): " +
                "$error — continuing upstream on the client's history",
        )
    }

    /** Diagnostics only. Each of these used to refuse the turn; none of them makes the client's
     *  history invalid, so they are logged and the turn proceeds without a rewrite for those ids. */
    private fun historyNotes(
        turn: CodexCodeModeBridge.Turn,
        key: String,
        digest: String,
    ): List<String> {
        val resultIds = turn.toolResults.map(CodeModeResult::id).toSet()
        val foreign = registry.foreignResultOwner(key, resultIds)
        val unknown = registry.unknownBridgeResults(key, resultIds)
        return buildList {
            if (registry.expiredHistory(key, digest, resultIds)) {
                add("expired code-mode history for this conversation; its client calls stay ordinary tool calls")
            }
            if (foreign != null) {
                add(
                    "code-mode results in this history belong to another session or model " +
                        "(record ${foreign.id.take(RECORD_ID_LOG_CHARS)}); they stay ordinary tool calls",
                )
            }
            if (unknown.isNotEmpty()) {
                add("unknown or expired code-mode tool results stay ordinary tool calls: $unknown")
            }
        }
    }

    private fun failure(message: String): TurnOutcome.Failure =
        TurnOutcome.Failure(ErrorType.INVALID_REQUEST, message)
}

private data class PlacedOwner(val record: CodeModeRecord, val bodyJson: String)

private class CodexCodeModeIdentity {
    fun turnKey(turn: CodexCodeModeBridge.Turn): String = digest(
        "${turn.sessionId}${0.toChar()}${turn.conversationKey}${0.toChar()}${turn.model}",
    )

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
