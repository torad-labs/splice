// NEW: V4-133, FEATURES.md §5/§6 — POST /api/playground: "one prompt through one head, raw request
// and response back, never recorded".
//
// Deviation (Oct 12, 2026, marlin): the send IS recorded now, because it is real spend. The head's budget weighs it
// first and a block answers 429 {error, outcome: "error:budget-blocked", blocked: "budget", detail, head}; an answered
// send leaves a Requests row flagged Compare, its economics entry and its budget spend, written by the head from
// :app (UpstreamPlaygroundProbe). What stays true below: the send does not go through the head's turn pipeline.
//
//   POST /api/playground   body {head: string, prompt: string, model?: string} -> {request: {...}, response: {...}}
//                           or {error: string} naming why (unknown head, no head-owned credential,
//                           the upstream call itself failing)
//
// THE SEND DOES NOT GO THROUGH THE TURN PIPELINE. The turn pipeline (:daemon-head TurnDriver/TurnTelemetry)
// carries a session, a trace and a retry loop a one-shot send has none of. So [PlaygroundSource] (implemented in
// :app, where the real upstream client and credentials already live — :daemon-control cannot see
// :daemon-head/provider-* types, module law) performs one independent upstream call, and the head's CompareSends
// writes what the send leaves in splice's stores: a budget refusal, its Over budget row alone; a send past the
// budget, however it ends (answered, failed, or cut off by the caller leaving), its Requests row, its economics
// entry and its budget spend. No trace is kept, and the request/response pair itself lives in the HTTP response
// body and nowhere else.
//
// ONE PROMPT, THROUGH THE TURN'S OWN BUILDER, NOT A SECOND TRANSLATION PIPELINE. The playground sends what
// "one prompt through one head" says: the one-message turn Claude Code would send with the prompt, on the
// head's pinned model or the model the body names (V4-444: the console's Playground compares models, two on
// one head as readily as two heads). The head's own provider builds the upstream request from that turn, the
// way it builds every real one, so the body, URL and headers are a turn's (V4-444: a hand-built copy per
// dialect drifted, and ChatGPT refused every send). See UpstreamPlaygroundProbe.kt (:app).
package splice.diagnostics.playground

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTag
import splice.http.JsonReply

/** How PlaygroundRoute reaches the daemon's ONE upstream probe — read at call time, the same
 *  discipline `BudgetSource`/`TeamSource` keep, because ControlPlane assigns it after
 *  construction. */
public fun interface PlaygroundSource {
    public operator fun invoke(): PlaygroundProbe?
}

/** The two-outcome shape [PlaygroundProbe.run] answers with — a sealed value rather than a suspend
 *  function that throws, because the upstream call failing (no credential, the vendor down, a
 *  timeout) is an ORDINARY outcome of a debugging tool, not an exceptional one. */
public sealed class PlaygroundOutcome

/** What one playground run produced, as JSON elements the route serializes VERBATIM — this file
 *  never interprets a field of either side, it only carries what [PlaygroundSource] returns. */
public data class PlaygroundResult(val request: JsonElement, val response: JsonElement) : PlaygroundOutcome()

/** One playground run failed before or during the upstream call (no credential, a refused dialect,
 *  a network failure). */
public data class PlaygroundFailure(val message: String) : PlaygroundOutcome()

/** The head's budget refused this send, as it refuses a turn: [message] is its sentence. Nothing was sent; the
 *  refusal has left its row on Requests. */
public data class PlaygroundBlocked(val head: String, val message: String) : PlaygroundOutcome()

/** The daemon's one upstream probe, implemented in :app where the heads' providers and credentials
 *  already live. [PlaygroundHead] is enough for it to resolve the rest itself: the head's key names
 *  the provider the daemon assembled that head with, and the probe builds and sends through it. */
public fun interface PlaygroundProbe {
    /** [model] is the model to run, or null for the head's pinned model. */
    public suspend fun run(head: PlaygroundHead, prompt: String, model: String?): PlaygroundOutcome
}

internal const val PLAYGROUND_UNWIRED = "the daemon wired no playground probe; /api/playground cannot run"

private const val BAD_PLAYGROUND_BODY = "the body must be {\"head\": string, \"prompt\": string, \"model\"?: string}"

@Serializable
private data class PlaygroundBody(val head: String = "", val prompt: String = "", val model: String? = null)

public class PlaygroundRoute(
    private val heads: PlaygroundHeadLookup,
    private val source: PlaygroundSource,
) {
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun run(body: String): JsonReply {
        val probe = source() ?: return refuse(HttpStatusCode.ServiceUnavailable, PLAYGROUND_UNWIRED)
        val parsed = playgroundBody(body) ?: return refuse(HttpStatusCode.BadRequest, BAD_PLAYGROUND_BODY)
        return runValidated(probe, parsed)
    }

    /** The request body; null for one that is not JSON and for one missing head/prompt, which get the same
     *  answer, one 400 naming the shape expected, so the parse failure has nothing more to say. */
    private fun playgroundBody(body: String): PlaygroundBody? = try {
        json.decodeFromString(PlaygroundBody.serializer(), body)
    } catch (_: IllegalArgumentException) {
        null
    }

    // Split out of run() (ReturnCount: max 3 per function) — the body-parsing guards live in
    // run() and the prompt/head validation plus the actual probe call live here.
    private suspend fun runValidated(probe: PlaygroundProbe, parsed: PlaygroundBody): JsonReply {
        if (parsed.prompt.isBlank()) return refuse(HttpStatusCode.BadRequest, "prompt must not be blank")
        val head = heads.byName(parsed.head).firstOrNull()
            ?: return refuse(HttpStatusCode.BadRequest, "unknown head: ${parsed.head}")
        return when (val outcome = probe.run(head, parsed.prompt, parsed.model?.takeIf { it.isNotBlank() })) {
            is PlaygroundResult -> JsonReply(HttpStatusCode.OK, resultJson(outcome))
            is PlaygroundFailure -> refuse(HttpStatusCode.BadGateway, outcome.message)
            is PlaygroundBlocked -> JsonReply(HttpStatusCode.TooManyRequests, blockedJson(outcome))
        }
    }

    // The outcome tag is the one a turn's refusal carries, so the console names it with the word it already has for it.
    private fun blockedJson(blocked: PlaygroundBlocked): String = buildJsonObject {
        put("error", blocked.message)
        put("outcome", OutcomeTag.BUDGET_BLOCKED.wire)
        put("blocked", "budget")
        put("head", blocked.head)
    }.toString()

    private fun resultJson(result: PlaygroundResult): String = buildJsonObject {
        put("request", result.request)
        put("response", result.response)
    }.toString()

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
