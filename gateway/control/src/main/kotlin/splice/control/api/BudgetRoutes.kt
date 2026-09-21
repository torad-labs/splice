// NEW: V4-133, FEATURES.md §5/§6 — GET/PUT /api/budgets. "spend budgets per head and per day with
// warn and block", one of the table-stakes items the operator kept in.
//
//   GET /api/budgets   {budgets: Budget[]}
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
package splice.control.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.budget.Budget
import splice.core.budget.BudgetStore
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.util.Cancellables

/** The daemon's budget store, read per request because ControlPlane assigns it after
 *  construction — the same discipline [TeamSource] and [TopologySource] keep. */
internal fun interface BudgetSource {
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

@Serializable
private data class BudgetsPayload(val budgets: List<Budget>)

internal class BudgetRoutes(private val source: BudgetSource, private val config: ConfigService) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun read(): JsonReply = withStore { store -> JsonReply(HttpStatusCode.OK, payloadJson(store.budgets())) }

    public fun write(body: String): JsonReply = withStore { store ->
        // ast-grep-ignore: kt-no-silent-result-collapse -- a body that is not JSON and a body of the wrong shape get the same answer, one 400 naming the shape expected, so the failure has nothing more to say
        val parsed = Cancellables.runCatchingCancellable { json.decodeFromString(BudgetsWireBody.serializer(), body) }
            .getOrNull() ?: return@withStore refuse(HttpStatusCode.BadRequest, "the body must be {\"budgets\": [...]}")
        val budgets = parsed.budgets.map { row -> Budget(row.head, row.dailyUsd, row.action ?: defaultAction()) }
        Cancellables.runCatchingCancellable { store.replace(budgets) }.fold(
            onSuccess = { JsonReply(HttpStatusCode.OK, payloadJson(it)) },
            onFailure = { failure ->
                refuse(HttpStatusCode.BadRequest, failure.message ?: "the budget write failed with no reason given")
            },
        )
    }

    private fun payloadJson(budgets: List<Budget>): String =
        json.encodeToString(BudgetsPayload.serializer(), BudgetsPayload(budgets))

    /** [Knob.BUDGET_DEFAULT_ACTION]'s live value — see the file header for why this reads live. */
    private fun defaultAction(): String =
        config.getConfig().asMap()[Knob.BUDGET_DEFAULT_ACTION.key] as? String
            ?: Knob.BUDGET_DEFAULT_ACTION.default as String

    private inline fun withStore(block: (BudgetStore) -> JsonReply): JsonReply {
        val store = source() ?: return refuse(HttpStatusCode.ServiceUnavailable, BUDGETS_UNWIRED)
        return block(store)
    }

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
