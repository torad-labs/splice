// NEW: serializes each conversation's code-mode replay, resume, and fresh-turn decisions.
package splice.provider.codex

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.turn.ErrorType
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeResult
import splice.spi.InterceptedRoundPost
import splice.spi.WireSink
import java.security.MessageDigest

private const val CODE_MODE_LOCK_STRIPES: Int = 64

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

internal class CodexCodeModeTurn(
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val driver: CodexCodeModeDriver,
    private val resume: CodexCodeModeResume,
) {
    private val identity = CodexCodeModeIdentity()
    private val locks = List(CODE_MODE_LOCK_STRIPES) { Mutex() }

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
        val completed = registry.completed(context.key)
        val historyFailure = historyFailure(context.turn, context.key, context.digest)
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        val owner = registry.owner(context.key, context.digest, resultIds)
        val replay = completed.any { it.lastDigest == context.digest }
        val completedHistory = wire.canonicalize(bodyJson, completed)
        val rewritten = if (completedHistory.error == null && owner != null) {
            wire.restoreBaseline(checkNotNull(completedHistory.bodyJson), owner)
        } else {
            completedHistory
        }
        val historyError = rewritten.error?.let(::failure)
        val canonicalBody = rewritten.bodyJson
        return when {
            historyFailure != null -> historyFailure
            historyError != null -> historyError
            owner?.phase == CodeModePhase.ACTIVE -> resume.active(owner, context, checkNotNull(canonicalBody))
            owner?.phase == CodeModePhase.LOST -> resume.lost(owner, context, checkNotNull(canonicalBody))
            replay -> driver.drive(context, null, checkNotNull(canonicalBody), context.post(canonicalBody))
            else -> driver.drive(context, initialOuter, checkNotNull(canonicalBody), context.post(canonicalBody))
        }
    }

    private fun historyFailure(
        turn: CodexCodeModeBridge.Turn,
        key: String,
        digest: String,
    ): TurnOutcome.Failure? {
        val resultIds = turn.toolResults.map(CodeModeResult::id).toSet()
        val foreign = registry.foreignResultOwner(key, resultIds)
        val unknown = registry.unknownBridgeResults(key, resultIds)
        return when {
            registry.expiredHistory(key, digest, resultIds) -> failure("expired code-mode history")
            foreign != null -> failure("code-mode tool result belongs to another session or model")
            unknown.isNotEmpty() -> failure("unknown or expired code-mode tool results: $unknown")
            else -> null
        }
    }

    private fun failure(message: String): TurnOutcome.Failure =
        TurnOutcome.Failure(ErrorType.INVALID_REQUEST, message)
}

private class CodexCodeModeIdentity {
    fun turnKey(turn: CodexCodeModeBridge.Turn): String = digest(
        "${turn.sessionId}${0.toChar()}${turn.conversationKey}${0.toChar()}${turn.model}",
    )

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
