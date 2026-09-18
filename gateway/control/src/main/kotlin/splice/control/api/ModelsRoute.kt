// NEW: V4-127, FEATURES.md §6 — GET /api/models, the console's models page source.
//
// THE JOIN RUNS IN BOTH DIRECTIONS, and that is the whole design of this payload. §4.8's column is
// "the tiers Claude Code will and will not get on this head", so a payload that reported only what
// RESOLVED could not serve it: a model that resolved but was never declared carries `slot: null`,
// and a slot that was DECLARED and resolved to nothing is its own row with `resolved: false`, never
// dropped. Silently omitting the tier the operator is missing is the one thing the contract says
// this page must not do, and it is the reason the route needs the DECLARED list as well as the
// catalog — the two are different facts, not two spellings of one.
//
// THE DECLARED LIST AND THE PROVIDER KEY ARRIVE AS A ROLE, not as fields on ManagedHead: that record
// is already a recorded constructor-width offender and the ratchet refused carrying them on it
// (DeclaredHeads holds the whole refusal). Read at CALL time, so the daemon may assign it after the
// server is constructed.
//
// `provider` IS IN THE CONTRACT, and this file is where that was learned: FEATURES.md §6 names it on
// every catalog row — "the registry's provider key for the head, decided 2026-09-18 with the daemon
// lead, V4-127: the models page groups by provider". A build that omitted it would ship a models page
// that cannot perform its own stated grouping, so it is emitted from the ROLE rather than from the
// head: same bytes, no ManagedHead field.
//
// AN UNWIRED ROSTER IS A NAMED 503, NEVER A PAYLOAD. With no declared list every model draws
// `slot: null` and no unresolved row is emitted at all, which reads as "the operator declared no
// tiers" — a confident false negative about the exact thing this page exists to display, and one the
// payload cannot be distinguished from the real answer.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.control.DeclaredHead
import splice.control.DeclaredHeads
import splice.control.ManagedHead
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.topology.HeadModel

/** Answered when the daemon never wired the declared roster. The named 5xx the header comment above
 *  explains: a payload without it would render as a head with no tiers declared. */
internal const val MODELS_UNWIRED =
    "the daemon did not wire the declared roster; /api/models cannot report slots or providers"

/** The `context_window_source` labels. A LABEL the console renders and NEVER parses — same contract
 *  as the compaction-instruction scopes — so a new value is a display change and not a protocol one.
 *
 *  THE KEY IS `context_window_source`, AND THIS FILE SHIPPED IT AS `window_source` FIRST. It pairs
 *  with `context_window` on the same row, so a reader needs no inference to know WHICH window's
 *  provenance it reports; the contract's own line had to spend a following sentence saying exactly
 *  that, and a name needing its own contract to explain it is the wrong name. The console declares
 *  the field NON-OPTIONAL in TS read off a live daemon, so the mismatch was invisible to the compiler
 *  and to every fixture — the fixtures spelled it the console's way, so the two hand-authored
 *  artifacts agreed with each other and disagreed with the daemon. */
private const val WINDOW_FROM_MODEL = "model"
private const val WINDOW_FROM_RULE = "rule"
private const val WINDOW_FROM_EXTRA = "extra-window"
private const val WINDOW_FROM_DEFAULT = "default"
private const val WINDOW_UNKNOWN = "unknown"

internal class ModelsRoute(private val heads: Map<String, ManagedHead>) {

    /** [declared] ARRIVES AT CALL TIME rather than being held — the routing lambda reads the server's
     *  own property as it calls, so the port is never captured (a captured port would be null forever
     *  against a daemon that wired it a moment later) and this route holds nothing to capture. It was
     *  a `() -> DeclaredHeads?` constructor seam first, which is the unnamed transposable shape
     *  kt-no-lambda-seam forbids; the fix is the argument, not a new role named for the lambda. */
    suspend fun models(call: ApplicationCall, declared: DeclaredHeads?) {
        val roster = declared?.invoke()
        if (roster == null) {
            refuse(call, MODELS_UNWIRED, HttpStatusCode.ServiceUnavailable)
            return
        }
        // A head the roster does not name is the wiring's gap, not an empty declaration: the role's
        // contract keeps an ABSENT key and a null value as different facts, and this is where the
        // difference is spent. Answering the whole route rather than one head's row because a
        // half-answered page is the same confident false negative, one head narrower.
        val unnamed = heads.keys.firstOrNull { it !in roster }
        if (unnamed != null) {
            refuse(call, "$MODELS_UNWIRED (no entry for head '$unnamed')", HttpStatusCode.ServiceUnavailable)
            return
        }
        call.respondText(modelsJson(roster), ContentType.Application.Json)
    }

    fun modelsJson(roster: Map<String, DeclaredHead>): String = buildJsonObject {
        putJsonArray("heads") {
            heads.forEach { (key, head) -> add(row(key, head, roster.getValue(key))) }
        }
    }.toString()

    /** [declared] is the head's own entry, non-null by the caller's check — its `models` may still be
     *  null, which is the operator having declared no tiers. */
    private fun row(key: String, head: ManagedHead, declared: DeclaredHead): JsonObject = buildJsonObject {
        val catalog = head.catalog
        put("head", key)
        // §6: the page GROUPS by provider, so it is emitted from the role — the same value
        // ManagedHead could not carry without widening past a recorded ceiling.
        put("provider", declared.provider)
        put("pinned_model", catalog?.pinnedModel.orEmpty())
        putJsonArray("models") {
            addResolved(this, catalog, declared.models)
            addUnresolved(this, catalog, declared.models)
        }
    }

    /** Every model the catalog resolved, each carrying the slot it was DECLARED under — or null,
     *  which is a real answer: the model is served on this head but no tier names it. */
    private fun addResolved(
        target: JsonArrayBuilder,
        catalog: ModelCatalog?,
        declared: List<HeadModel>?,
    ) {
        catalog?.models.orEmpty().forEach { entry ->
            target.add(
                buildJsonObject {
                    put("id", entry.id)
                    put("label", entry.label)
                    put("description", entry.description)
                    put("context_window", entry.contextWindow)
                    put("context_window_source", WINDOW_FROM_MODEL)
                    put("resolved", true)
                    put("slot", declared?.firstOrNull { it.id == entry.id }?.slot)
                    put("pinned", isPinned(catalog, entry))
                    entry.rates?.let { put("rates", ratesJson(it)) }
                },
            )
        }
    }

    /** The declared slots that resolved to NOTHING. Their own rows, with a reason, because the
     *  whole point of the page is to show the operator what they are not getting. */
    private fun addUnresolved(
        target: JsonArrayBuilder,
        catalog: ModelCatalog?,
        declared: List<HeadModel>?,
    ) {
        val resolvedIds = catalog?.models.orEmpty().map(ModelEntry::id).toSet()
        declared.orEmpty().filterNot { it.id in resolvedIds }.forEach { slot ->
            target.add(
                buildJsonObject {
                    put("id", slot.id)
                    put("label", "")
                    put("description", "")
                    put("context_window", windowFor(slot.id, catalog))
                    put("context_window_source", windowSourceFor(slot.id, catalog))
                    put("resolved", false)
                    put("slot", slot.slot)
                    put("pinned", false)
                    put("reason", unresolvedReason(slot.id, catalog))
                },
            )
        }
    }

    private fun isPinned(catalog: ModelCatalog?, entry: ModelEntry): Boolean =
        catalog?.pinnedModel?.isNotEmpty() == true && catalog.pinnedModel == entry.id

    /** WHERE this row's window number comes from. Only a row that resolved has the model's own
     *  declared window; an unresolved slot can still be given one by a prefix rule, an extra window
     *  or the head's default, and saying which is the difference between a number an operator can
     *  check and a number they have to trust. */
    private fun windowSourceFor(id: String, catalog: ModelCatalog?): String = when {
        catalog == null -> WINDOW_UNKNOWN
        catalog.models.any { it.id == id } -> WINDOW_FROM_MODEL
        catalog.extraWindows.any { it.id == id } -> WINDOW_FROM_EXTRA
        catalog.windowRules.any { id.startsWith(it.prefix) } -> WINDOW_FROM_RULE
        else -> WINDOW_FROM_DEFAULT
    }

    private fun windowFor(id: String, catalog: ModelCatalog?): Long? = when {
        catalog == null -> null
        else -> catalog.models.firstOrNull { it.id == id }?.contextWindow
            ?: catalog.extraWindows.firstOrNull { it.id == id }?.contextWindow
            ?: catalog.windowRules.filter { id.startsWith(it.prefix) }.maxByOrNull { it.prefix.length }?.contextWindow
            ?: catalog.defaultContextWindow
    }

    /** WHY the slot resolved to nothing, in the operator's terms. A missing model and a model that
     *  exists upstream but is absent from the declared catalog are different problems with different
     *  fixes, and the console shows this sentence verbatim. */
    private fun unresolvedReason(id: String, catalog: ModelCatalog?): String = when {
        catalog == null -> "this head declares no catalog, so nothing could be resolved for it"
        catalog.windowRules.any { id.startsWith(it.prefix) } ->
            "declared as a slot and matched a window rule, but the catalog lists no such model"
        else -> "declared as a slot, but the catalog lists no model with this id"
    }

    private fun ratesJson(rates: ModelRates): JsonObject = buildJsonObject {
        put("input", rates.input)
        put("output", rates.output)
        put("cache_read", rates.cacheRead)
        rates.cacheWrite?.let { put("cache_write", it) }
    }

    private suspend fun refuse(call: ApplicationCall, message: String, status: HttpStatusCode) {
        call.respondText(
            buildJsonObject { put("error", message) }.toString(),
            ContentType.Application.Json,
            status,
        )
    }
}
