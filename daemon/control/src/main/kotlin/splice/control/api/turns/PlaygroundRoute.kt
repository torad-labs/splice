// NEW: V4-133, FEATURES.md §5/§6 — POST /api/playground: "one prompt through one head, raw request
// and response back, never recorded".
//
//   POST /api/playground   body {head: string, prompt: string} -> {request: {...}, response: {...}}
//                           or {error: string} naming why (unknown head, no head-owned credential,
//                           the upstream call itself failing)
//
// NEVER RECORDED MEANS THE DAEMON, NOT ONLY THE CONSOLE. The normal turn pipeline
// (:daemon-head TurnDriver/TurnTelemetry) writes a perf row, a trace record when capture is on, and
// folds tokens into economics on EVERY turn it serves — there is no flag on that path to skip
// those writes, and adding one would touch the hot turn-completion code every real request runs
// through, outside this row's fence. So this route does not send the prompt through a head's own
// server at all: [PlaygroundSource] (implemented in :app, where the real upstream client and
// credentials already live — :daemon-control cannot see :daemon-head/provider-* types, module law) performs
// one independent upstream call and nothing here or in it writes to any splice-owned store. The
// request/response pair lives in the HTTP response body and nowhere else, matching the doctor
// page's own PlaygroundState comment ("no store, no storage, no history").
//
// DELIBERATELY MINIMAL, NOT A SECOND TRANSLATION PIPELINE. A real turn carries tool schemas, the
// system prompt layers, compaction and cache-control markers — reproducing that here would
// duplicate :daemon-head's dialect modules, incorrectly, outside their own tests. The playground sends
// exactly what "one prompt through one head" says: the head's pinned model and the prompt text,
// nothing else. See PlaygroundProbe.kt (:app) for the per-dialect request shape.
package splice.control.api.turns

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.ManagedHead
import splice.control.api.HeadResolver
import splice.control.api.sessions.JsonReply
import splice.core.util.Cancellables

/** How PlaygroundRoute reaches the daemon's ONE upstream probe — read at call time, the same
 *  discipline [BudgetSource]/[TeamSource] keep, because ControlPlane assigns it after
 *  construction. */
internal fun interface PlaygroundSource {
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

/** The daemon's one upstream probe, implemented in :app where the real upstream client, topology
 *  and credentials already live. [ManagedHead] is enough for it to resolve the rest itself: the
 *  head's key names it in the topology this call re-reads fresh (playground runs are rare and
 *  interactive; a per-call read is simpler than threading a cached Topology through ControlPlane
 *  for one route). */
public fun interface PlaygroundProbe {
    public suspend fun run(head: ManagedHead, prompt: String): PlaygroundOutcome
}

internal const val PLAYGROUND_UNWIRED = "the daemon wired no playground probe; /api/playground cannot run"

private const val BAD_PLAYGROUND_BODY = "the body must be {\"head\": string, \"prompt\": string}"

@Serializable
private data class PlaygroundBody(val head: String = "", val prompt: String = "")

internal class PlaygroundRoute(
    private val resolver: HeadResolver,
    private val source: PlaygroundSource,
) {
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun run(body: String): JsonReply {
        val probe = source() ?: return refuse(HttpStatusCode.ServiceUnavailable, PLAYGROUND_UNWIRED)
        // ast-grep-ignore: kt-no-silent-result-collapse -- a body that is not JSON and a body missing head/prompt get the same answer, one 400 naming the shape expected, so the failure has nothing more to say
        val parsed = Cancellables.runCatchingCancellable { json.decodeFromString(PlaygroundBody.serializer(), body) }
            .getOrNull() ?: return refuse(HttpStatusCode.BadRequest, BAD_PLAYGROUND_BODY)
        return runValidated(probe, parsed)
    }

    // Split out of run() (ReturnCount: max 3 per function) — the body-parsing guards live in
    // run() and the prompt/head validation plus the actual probe call live here.
    private suspend fun runValidated(probe: PlaygroundProbe, parsed: PlaygroundBody): JsonReply {
        if (parsed.prompt.isBlank()) return refuse(HttpStatusCode.BadRequest, "prompt must not be blank")
        val head = resolver.headByName(parsed.head).firstOrNull()
            ?: return refuse(HttpStatusCode.BadRequest, "unknown head: ${parsed.head}")
        return when (val outcome = probe.run(head, parsed.prompt)) {
            is PlaygroundResult -> JsonReply(HttpStatusCode.OK, resultJson(outcome))
            is PlaygroundFailure -> refuse(HttpStatusCode.BadGateway, outcome.message)
        }
    }

    private fun resultJson(result: PlaygroundResult): String = buildJsonObject {
        put("request", result.request)
        put("response", result.response)
    }.toString()

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
