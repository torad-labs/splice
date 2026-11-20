// NEW: V4-358. What a request says about the window its client holds.
//
// A row spelled with the 1M hint reaches the head WITHOUT it: Claude Code 2.1.283 strips /\[1m\]/i from
// the id before it writes the body's `model` (its `i0(Mn(id))`), and instead adds the 1M-context beta to
// `anthropic-beta`, for exactly the ids /\[1m\]/i matches (its `Kd`, the same test that lifts the image
// cap and fixes the window at 1e6). Measured Sep 28 against a local stub with the real client: model
// `gpt-6-sol[1m]` sent body model `gpt-6-sol` and `context-1m-2025-08-07`; `gpt-6-sol` sent no such beta.
// So the beta is the one place the head can read which window the client divides by on THIS request, and
// scaling the counts by client/declared needs it.
//
// #706 (2026-11-20): any other env-governed id is divided by the CLAUDE_CODE_MAX_CONTEXT_TOKENS of the
// process that sent it, and a session id does not name a process: a resumed id kept the window an earlier
// process posted, and a session whose main thread runs `[1m]` never posts a new one. A launch names its window
// in [CLIENT_WINDOW_HEADER], so the request says it, the way it says the 1M beta.
package splice.head

import io.ktor.server.application.ApplicationCall
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.CLIENT_WINDOW_HEADER
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
import splice.core.turn.TurnMeta

/** The window a turn's counts are scaled against: what its own request says the client holds, else what
 *  the session's status-line posts taught ([ClientWindows]), else null (the launch env's). Per request
 *  first, because a session can move between a row spelled 1M and one that is not with /model, and only
 *  the request knows which it is on; and because a session id moves between processes, and only the request
 *  knows which process sent it. */
internal class ClientWindowWitness(private val posted: ClientWindows) {
    fun of(call: ApplicationCall, catalog: ModelCatalog, meta: TurnMeta): Long? {
        val headers = call.request.headers
        val betas = headers.getAll(BETA_HEADER).orEmpty().flatMap { it.split(',') }
        val said = CLAUDE_CODE_ONE_MILLION.takeIf { betas.any { it.trim().startsWith(ONE_MILLION_BETA) } }
        // The launch's window describes only an id the client sizes from the launch env; a `claude-` id is
        // sized from the client's own table, which only its status-line post reveals.
        val launched = headers[CLIENT_WINDOW_HEADER]?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            ?.takeIf { catalog.envGoverned(meta.route.originalModel) }
        return said ?: launched ?: posted.windowFor(meta.scope.sessionId)
    }
}

private const val BETA_HEADER = "anthropic-beta"

// why: the client's 1M-context beta is dated ("context-1m-2025-08-07"); a later date is the same claim
private const val ONE_MILLION_BETA = "context-1m-"
