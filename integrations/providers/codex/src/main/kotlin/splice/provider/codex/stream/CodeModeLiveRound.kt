// NEW: the terminal upstream outcome owns final source identity and continuity, not a client step.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.RedirectableRoundPost
import splice.upstream.TurnEnd
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.io.IOException

internal fun interface CodeModeStreamAdmission {
    fun admit(call: GatewayCustomCall): CodeModeRecord
}

internal class CodeModeLiveRound(
    config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    wire: CodexCodeModeWire,
    admission: CodeModeStreamAdmission,
    sink: WireSink,
) {
    private val capture = CodeModeSourceCapture(config, registry, wire, admission)
    val source = capture.source
    val ready = capture.ready
    val switching = CodeModeSwitchingSink(sink, CodeModeSourceObserver(::observe))
    private val record: CodeModeRecord? get() = capture.record
    private var finished: Deferred<TurnOutcome>? = null

    private val lifecycle = Any()
    private var headStopped = false

    @Volatile private var upstreamEnded = false

    fun start(scope: CoroutineScope, post: RedirectableRoundPost, body: String, end: TurnEnd) {
        check(finished == null)
        finished = scope.async(start = CoroutineStart.UNDISPATCHED) {
            switching.ownedBy(this)
            try {
                val outcome = post.into(body, switching)
                upstreamEnded = true
                finish(outcome)
                outcome
            } catch (error: CancellationException) {
                cancelled(error)
                throw error
            } catch (error: IOException) {
                failed(error)
            } catch (error: CodeModePersistenceException) {
                failed(error)
            } catch (error: IllegalStateException) {
                failed(error)
            } catch (error: IllegalArgumentException) {
                failed(error)
            } finally {
                if (!ready.isCompleted) ready.complete(null)
            }
        }.also { it.invokeOnCompletion { end.ended() } }
    }

    private fun finish(outcome: TurnOutcome) = synchronized(lifecycle) {
        if (headStopped) throw CancellationException("code-mode head stopped")
        capture.finish(outcome)
    }

    private fun cancelled(error: CancellationException) {
        upstreamEnded = true
        source.fail("upstream source cancelled; source was not rerun")
        synchronized(lifecycle) {
            if (!headStopped) {
                record?.takeUnless(CodeModeRecord::terminal)
                    ?.let { registry.lose(it, "upstream source cancelled; source was not rerun", error) }
            }
        }
    }

    private fun failed(error: Exception): TurnOutcome.Failure = synchronized(lifecycle) {
        upstreamEnded = true
        if (headStopped) throw CancellationException("code-mode head stopped", error)
        val detail = "upstream source failed; source was not rerun"
        source.fail(detail)
        if (error is CodeModePersistenceException) {
            if (!ready.isCompleted) ready.completeExceptionally(error)
            throw error
        }
        record?.takeUnless(CodeModeRecord::terminal)?.let { registry.lose(it, detail) }
        TurnOutcome.Failure(detail, cause = FailureCause.INTERNAL, phase = FailurePhase.MID_OUTPUT)
    }

    fun owns(turn: CodexCodeModeBridge.Turn): Boolean = synchronized(lifecycle) {
        val current = record ?: return@synchronized false
        current.sessionId == turn.sessionId && current.key == CodeModeTurnIdentity().turnKey(turn) &&
            turn.toolResults.any { it.id in current.clientIds() }
    }

    suspend fun outcome(): TurnOutcome = checkNotNull(finished).await()

    fun cancel() {
        if (!upstreamEnded) finished?.cancel()
    }

    /** The synchronous registry stop owns persistence; a cancelled old reader cannot overwrite the next head. */
    fun stop() {
        synchronized(lifecycle) { headStopped = true }
        cancel()
    }

    private fun observe(event: CustomToolSource) = synchronized(lifecycle) {
        if (headStopped) throw CancellationException("code-mode head stopped")
        capture.observe(event)
    }
}
