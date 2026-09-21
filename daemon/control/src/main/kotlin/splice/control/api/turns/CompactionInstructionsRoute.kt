// NEW: V4-136 FEATURES.md §6 — GET /api/compaction/instructions, the console's view of which
// compaction instructions are in play, by scope, source and length.
//
// TEXT NEVER CROSSES THIS WIRE. The route reports where an instruction came from and how long it
// is; the operator who wants to read it opens the file the source names. That is not squeamishness —
// a compaction instruction is prompt material, and a read-only inspection route that echoed it would
// turn every console poll into a path that copies prompts into a browser.
//
// THE LOOKUP IS NOT RE-IMPLEMENTED HERE. The enumeration lives in CompactionInstructions.rules(),
// over the same rule table resolve() reads and through the same live file cache, so this route and
// a real compaction cannot disagree about what is configured. This file only filters and shapes.
package splice.control.api.turns

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.compaction.CompactionInstructions
import splice.core.compaction.CompactionScope
import splice.core.compaction.EffectiveCompactionInstructions

/** Answered when the daemon never wired the table. NOT a 404 and NOT an empty list: the console
 *  reads 404 on this path as "route not built yet", and an empty list would tell an operator that no
 *  instructions are configured while the daemon compacts with them — a confident false negative,
 *  which is worse than an error. The text names what the daemon failed to do. */
internal const val COMPACTION_UNWIRED =
    "the daemon did not wire the compaction table; /api/compaction/instructions cannot report it"

internal class CompactionInstructionsRoute(
    private val resolver: HeadResolver,
) {

    /** [table] ARRIVES AT CALL TIME, not construction time: ControlPlane assigns the property right
     *  after the server is built, so a route that captured the value would capture null forever. It
     *  was a `() -> CompactionInstructions?` constructor seam until kt-no-lambda-seam flagged it —
     *  the same unnamed transposable shape, fixed the same way, by the argument rather than by a new
     *  role invented to name the lambda. */
    suspend fun instructions(call: ApplicationCall, table: CompactionInstructions?) {
        val head = call.request.queryParameters["head"].orEmpty()
        val matches = if (head.isBlank()) emptyList() else resolver.headByName(head)
        if (matches.isEmpty()) {
            // 400 naming the head, never 404: 404 on this path means route-not-built to the console.
            call.respondText(
                buildJsonObject { put("error", "unknown head: $head") }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        if (table == null) {
            call.respondText(
                buildJsonObject { put("error", COMPACTION_UNWIRED) }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        val roster = matches.mapNotNull { it.catalog }.flatMap { it.availableModelIds() }.toSet()
        val scopes = table.rules().filter { belongsToHead(it, roster) }.map { rule ->
            buildJsonObject {
                put("scope", rule.scope.wire)
                put("source", rule.source)
                // live length: 0 for an explicit opt-out (empty text), null when the text is
                // unavailable (an unreadable file, which the source label already says).
                put("chars", rule.text?.length)
            }
        }
        call.respondText(
            buildJsonObject { put("scopes", buildJsonArray { scopes.forEach { add(it) } }) }.toString(),
            ContentType.Application.Json,
        )
    }

    /** A model rule applies to a head only if that head's roster carries the model; every other
     *  scope is head-independent and always reported.
     *
     *  The model is read from the SOURCE LABEL core composes (`model:<id>`, CompactionInstructions
     *  .kt:76) rather than parsed out of a free string: constructing the label the roster implies and
     *  comparing is the safe direction, because a format change breaks the comparison loudly instead
     *  of silently matching nothing. */
    private fun belongsToHead(rule: EffectiveCompactionInstructions, roster: Set<String>): Boolean =
        rule.scope != CompactionScope.MODEL || roster.any { rule.source == "${CompactionScope.MODEL.wire}:$it" }
}
