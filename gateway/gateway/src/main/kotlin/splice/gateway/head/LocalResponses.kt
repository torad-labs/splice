// NEW: the two response shapes the proxy serves WITHOUT an upstream turn (2026-09-05): the activity
// label (ActivityLabel — composed here) and a detached compaction's recorded answer (CompactionReplay
// — replayed here). Both hold the admission slot for their own duration only, ride the same quota
// headers and usage payload a driven turn does, and write through Ktor exactly as TurnStreamer does,
// so Claude Code cannot tell them from a model's answer — which is the point.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import splice.core.turn.Usage
import splice.gateway.wire.CollectingTerminal
import splice.gateway.wire.SseEmitterFactory
import splice.gateway.wire.TurnTerminal
import splice.gateway.wire.TurnWiring
import splice.spi.Provider

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
            deps.clientWindows.windowFor(local.sessionId),
        )
        quotaHeaders(call)
        if (local.stream) {
            call.respondTextWriter(ContentType.Text.EventStream) {
                val emitter = emitters.create(
                    write = { frame ->
                        write(frame)
                        flush()
                    },
                    model = local.model,
                    usagePayload = usage,
                )
                emitText(emitter, local.text)
            }
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
     *  a delivered replay consumes it. */
    suspend fun replay(call: ApplicationCall, replayed: Preparation.Replay) {
        quotaHeaders(call)
        var frames = 0
        call.respondTextWriter(ContentType.Text.EventStream) {
            replayed.recording.follow { frame ->
                write(frame)
                flush()
                frames += 1
            }
        }
        replay.consumed(replayed.key)
        val who = replayed.sessionId?.let { "session ${it.take(TAG_CHARS)}" } ?: "no session"
        deps.log(
            "[${provider.key}] compaction answer replayed ($who, $frames frames; the retry cost no upstream turn)\n",
        )
    }

    // The head's quota windows ride every response (TurnStreamer / CollectTurn do the same).
    private fun quotaHeaders(call: ApplicationCall) {
        deps.quota?.clientHeaders()?.forEach { (name, value) -> call.response.header(name, value) }
    }

    private suspend fun emitText(terminal: TurnTerminal, text: String) {
        terminal.ensureStarted()
        val block = terminal.openText()
        terminal.textDelta(block, text)
        terminal.closeAll()
        terminal.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())
    }
}

private const val TAG_CHARS = 8
