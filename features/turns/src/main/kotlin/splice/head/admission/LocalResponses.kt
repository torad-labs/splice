// NEW: the two response shapes the proxy serves WITHOUT an upstream turn (2026-09-05): the activity
// label (ActivityLabel — composed here) and a detached compaction's recorded answer (CompactionReplay
// — replayed here). Both hold the admission slot for their own duration only, ride the same quota
// headers and usage payload a driven turn does, and write through SseResponse exactly as TurnStreamer does,
// so Claude Code cannot tell them from a model's answer — which is the point.
package splice.head.admission

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.head.HeadDeps
import splice.head.compaction.CompactionReplay
import splice.head.turn.Preparation
import splice.head.turn.SESSION_TAG_CHARS
import splice.head.wire.CollectingTerminal
import splice.head.wire.FrameWrite
import splice.head.wire.SseEmitterFactory
import splice.head.wire.SseResponse
import splice.head.wire.TurnTerminal
import splice.head.wire.TurnWiring
import splice.upstream.Provider

internal class LocalResponses(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val replay: CompactionReplay,
) {
    private val emitters = SseEmitterFactory()
    private val wiring = TurnWiring()

    /** One text block, an end_turn terminal, zero usage: the side query is not a context read. */
    suspend fun answer(call: ApplicationCall, local: Preparation.Local) {
        val usage = wiring.usagePayloadBuilderFor(
            provider.catalog,
            local.model,
            deps.stores.clientWindows.windowFor(local.sessionId),
        )
        quotaHeaders(call, local.sessionId)
        if (local.stream) {
            val stream = SseResponse { out ->
                val emitter = emitters.create(
                    write = { frame ->
                        out.write(frame)
                        out.flush()
                    },
                    model = local.model,
                    usagePayload = usage,
                )
                emitText(emitter, local.text)
            }
            call.respond(stream)
        } else {
            val terminal = CollectingTerminal(local.model, usage)
            emitText(terminal, local.text)
            call.respondText(
                terminal.responseBody().toString(),
                ContentType.Application.Json,
                HttpStatusCode.fromValue(terminal.httpStatus()),
            )
        }
    }

    /** Every recorded frame, then every one still arriving while the compaction is in flight. A
     *  client that hangs up on the replay too leaves the recording in place for the next retry;
     *  a delivered replay consumes it. A recording that ends WITHOUT a clean terminal (the drive
     *  was cancelled: its own client long gone, the seal abandons and writes nothing) is ended
     *  here with the honest error frame the seal gives an attached client, so this client retries
     *  as well — and finds the entry gone (CompactionReplay.finish), so that attempt runs upstream. */
    suspend fun replay(call: ApplicationCall, replayed: Preparation.Replay) {
        quotaHeaders(call, replayed.sessionId)
        var frames = 0
        var whole = false
        val stream = SseResponse { out ->
            whole = replayed.recording.follow { frame ->
                out.write(frame)
                out.flush()
                frames += 1
            }
            if (!whole) {
                sealFollower(replayed) { frame ->
                    out.write(frame)
                    out.flush()
                }
            }
        }
        call.respond(stream)
        replay.consumed(replayed.key)
        val who = replayed.sessionId?.let { "session ${it.take(SESSION_TAG_CHARS)}" } ?: "no session"
        deps.log(
            if (whole) {
                "[${provider.key}] compaction answer replayed ($who, $frames frames; the retry cost no upstream turn)\n"
            } else {
                "[${provider.key}] compaction retry followed a compaction that did not finish ($who, $frames frames; " +
                    "sealed with an error, the next retry runs upstream)\n"
            },
        )
    }

    // The one failure ending the wire knows (SseEmitter.emitError, L3): an overloaded error, which
    // Claude Code retries — the same frame CancellationSeal writes for an attached client.
    private suspend fun sealFollower(replayed: Preparation.Replay, write: FrameWrite) {
        val emitter = emitters.create(
            write = write,
            model = replayed.model,
            usagePayload = wiring.usagePayloadBuilderFor(
                provider.catalog,
                replayed.model,
                deps.stores.clientWindows.windowFor(replayed.sessionId),
            ),
        )
        emitter.emitError(
            ErrorType.OVERLOADED,
            "${provider.key}: the compaction this retry followed did not finish — retry",
        )
    }

    // The quota windows ride every response (TurnStreamer / CollectTurn do the same): on a pooled
    // head the SESSION's selected account, not the primary's, or the bars would flip on every locally
    // answered side query (review 2026-09-14).
    private fun quotaHeaders(call: ApplicationCall, sessionId: String?) {
        deps.turnQuota.forSession(sessionId, null)
            ?.clientHeaders()
            ?.forEach { (name, value) -> call.response.header(name, value) }
    }

    private suspend fun emitText(terminal: TurnTerminal, text: String) {
        terminal.ensureStarted()
        val block = terminal.openText()
        terminal.textDelta(block, text)
        terminal.closeAll()
        terminal.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())
    }
}
