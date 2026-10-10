// NEW: V4-133, FEATURES.md §5/§6 — GET/PUT /api/budgets. "spend budgets per head and per day with
// warn and block", one of the table-stakes items the operator kept in.
//
//   GET /api/budgets   {budgets: Budget[], unreadable: string|null, day_resets_at_epoch_ms: number|null} —
//                       unreadable names why the list is empty when budgets.json does not parse (V4-296);
//                       every head runs unbudgeted then. day_resets_at_epoch_ms is when the budget day next
//                       rolls over, as admission draws it, so a console on another clock draws the same day
//   PUT /api/budgets   body {budgets: Budget[]} -> the set as SAVED (console/src/entities/budget/api:
//                       "the store takes what the daemon now holds, rather than the request: a
//                       value the daemon clamped or refused must not read as applied")
//
// A BUDGET ROW WITH NO action IS FILLED FROM THE Knob-BACKED DEFAULT (Knob.BUDGET_DEFAULT_ACTION,
// "warn"), read live — never snapshotted — so an operator can tighten the default without a
// restart; this is the "a Knob-backed default" FEATURES.md §6 names for this route. A row that
// names its own action keeps it untouched.
//
// UNWIRED IS NOT EMPTY: no budget store answers 503 naming it, never an empty list — the same
// distinction /api/teams already draws (an empty budgets.json is legitimately "nothing budgeted",
// which is indistinguishable from an unwired daemon unless the two answer differently).
package splice.usage.budgets

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.util.Cancellables
import splice.http.JsonReply
import splice.usage.UsageHeadLookup
import splice.usage.economics.UNPRICED_REASON
import splice.usage.perf.HeadPriceGap

/** The daemon's budget store, read per request because ControlPlane assigns it after
 *  construction — the same discipline [TeamSource] and [splice.core.topology.TopologyWriterSource] keep. */
public fun interface BudgetSource {
    public operator fun invoke(): BudgetStore?
}

internal const val BUDGETS_UNWIRED = "the daemon wired no budget store; /api/budgets cannot report it"

/** The PUT body's own row: [action] is OPTIONAL here (the Knob default fills it), unlike
 *  [Budget.action] which the store requires — the wire contract and the stored invariant are
 *  deliberately different types. */
@Serializable
private data class BudgetWire(
    val head: String,
    @SerialName("daily_usd") val dailyUsd: Double? = null,
    val action: String? = null,
)

@Serializable
private data class BudgetsWireBody(val budgets: List<BudgetWire> = emptyList())

public class BudgetRoutes(
    private val source: BudgetSource,
    private val config: ConfigService,
    /** Where each command's billing is read, for the reason its turns with no price have none. */
    private val lookup: UsageHeadLookup? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun read(): JsonReply = read(null)

    /** The daemon passes its enforcement owner; an unwired or unpriced ledger never appears as zero spend. */
    public fun read(enforcement: BudgetEnforcement?): JsonReply = withStore { store ->
        val read = store.read()
        JsonReply(HttpStatusCode.OK, payloadJson(read.budgets, read.unreadable, enforcement))
    }

    public fun write(body: String): JsonReply = write(body, null)

    /** Writes caps only. Spend remains a read of the same head-wide enforcement ledger. */
    public fun write(body: String, enforcement: BudgetEnforcement?): JsonReply = withStore { store ->
        // A body that is not JSON and a body of the wrong shape get the same answer, one 400 naming the shape
        // expected, so the failure has nothing more to say.
        val parsed = try {
            json.decodeFromString(BudgetsWireBody.serializer(), body)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return@withStore refuse(HttpStatusCode.BadRequest, "the body must be {\"budgets\": [...]}")
        val budgets = parsed.budgets.map { row -> Budget(row.head, row.dailyUsd, row.action ?: defaultAction()) }
        Cancellables.runCatchingCancellable { store.replace(budgets) }.fold(
            onSuccess = { JsonReply(HttpStatusCode.OK, payloadJson(it, enforcement = enforcement)) },
            onFailure = { failure ->
                refuse(HttpStatusCode.BadRequest, failure.message ?: "the budget write failed with no reason given")
            },
        )
    }

    private fun payloadJson(
        budgets: List<Budget>,
        unreadable: String? = null,
        enforcement: BudgetEnforcement? = null,
    ): String = buildJsonObject {
        put("unreadable", unreadable)
        // The instant the budget day next rolls over, drawn where admission draws it. A console on another clock
        // computing its own midnight disagreed with the daemon (re-review, Oct 10); null when nothing is wired.
        put("day_resets_at_epoch_ms", enforcement?.dayResetsAtMs())
        putJsonArray("budgets") {
            budgets.forEach { budget ->
                val fields = json.encodeToJsonElement(Budget.serializer(), budget).jsonObject
                val spend = enforcement?.spending(budget.head)
                val row = buildJsonObject {
                    fields.forEach { (key, value) -> put(key, value) }
                    put("used_usd", spend?.usedUsd)
                    put("remaining_usd", spend?.remainingUsd)
                    put("unpriced_turns", spend?.unpricedTurns)
                    HeadPriceGap.wire(lookup, budget.head)?.let { put(UNPRICED_REASON, it) }
                    put("spend_complete", spend?.complete)
                    put("spend_pending", spend?.pending)
                }
                add(row)
            }
        }
    }.toString()

    /** [Knob.BUDGET_DEFAULT_ACTION]'s live value — see the file header for why this reads live. */
    private fun defaultAction(): String =
        config.getConfig().asMap()[Knob.BUDGET_DEFAULT_ACTION.key] as? String
            ?: Knob.BUDGET_DEFAULT_ACTION.text()

    private inline fun withStore(block: (BudgetStore) -> JsonReply): JsonReply {
        val store = source() ?: return refuse(HttpStatusCode.ServiceUnavailable, BUDGETS_UNWIRED)
        return block(store)
    }

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
