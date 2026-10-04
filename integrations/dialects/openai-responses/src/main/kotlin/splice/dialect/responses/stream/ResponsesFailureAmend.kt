// NEW: RC-4 / tool-surface amendBodyOnFailure for ResponsesProvider
// (concentration, 2026-08-19). Same-package.
package splice.dialect.responses.stream

import splice.core.util.LogSink
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.ReasoningCache
import splice.dialect.responses.reasoning.ReasoningCachePolicy
import splice.dialect.responses.tools.ToolSearchMode
import splice.dialect.responses.tools.ToolSurfaceLatch
import splice.dialect.responses.tools.ToolSurfaceRecovery
import splice.upstream.failure.FailureRules

internal class ResponsesFailureAmend(
    private val quirks: ResponsesQuirks,
    private val cachePolicy: ReasoningCachePolicy,
    private val reasoningCache: ReasoningCache,
    private val surfaceRecovery: ToolSurfaceRecovery,
    private val toolSurfaceLatch: ToolSurfaceLatch,
    private val log: LogSink,
) {
    /** RC-4: a 400 rejecting stale encrypted reasoning strips the injected items and retries
     *  once (NEVER-BELOW-STATUS-QUO law); every other failure keeps the plain retry plan. A 400
     *  rejecting the tool-surface shape strips the tool_search entry, retries once, and closes the
     *  latch so every LATER turn on this provider instance builds the full status-quo request.
     *  Keyed off the SAME classifier as the retry plan's GIVE_UP (review 2026-07-24: a narrower
     *  literal match here let any upstream wording drift skip the recovery entirely).
     *  Hosted definitions ride top-level tools, so its amend can retry all-eager immediately.
     *  Client mode omits deferred schemas from the request and recovers eager-only for this turn;
     *  the latch restores the full set on later turns. Both transitions are logged. */
    fun amendBodyOnFailure(status: Int, responseText: String, bodyJson: String): String? = when {
        FailureRules().isEncryptedContentError(status, responseText) ->
            cachePolicy.stripStaleReasoning(bodyJson, reasoningCache)
        surfaceRecovery.isToolSurfaceRejection(
            status,
            responseText,
            quirks.toolSurface?.mode ?: ToolSearchMode.CLIENT,
        ) ->
            surfaceRecovery.dropToolSearchTool(bodyJson, quirks.toolSurface?.mode ?: ToolSearchMode.CLIENT)?.also {
                if (toolSurfaceLatch.close()) logToolSurfaceLatchClosed()
            }
        else -> null
    }

    /** The latch's one observable signal, through the injected daemon sink so it reaches
     *  /mgmt/logs and not stderr alone (wall kt-no-println, 2026-07-27; it used to be a bare
     *  System.err.println on the premise that no logger reaches this module — one now does). Guarded by [ToolSurfaceLatch.close]'s CAS return, so this
     *  fires EXACTLY ONCE per provider instance — never once per turn, since every turn after the
     *  close reads the latch already-closed and never re-enters this branch. */
    private fun logToolSurfaceLatchClosed() {
        val thisTurn = if (quirks.toolSurface?.mode == ToolSearchMode.HOSTED) {
            "this turn retried with all tools eager"
        } else {
            "this turn recovered eager-only (one turn below status quo)"
        }
        log(
            "[${quirks.providerTag}] tool-surface latch closed: backend rejected the tool_search " +
                "shape; $thisTurn, every later turn on this provider instance builds the full eager surface.",
        )
    }
}
