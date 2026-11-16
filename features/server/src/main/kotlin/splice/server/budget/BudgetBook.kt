// NEW: server mode (docs/specs/server-mode.md, F5, person-budgets). The budgets the operator set with `splice server
// budget set`: a daily and a monthly limit in USD for one person or for a team, and whether reaching one blocks that
// person's or that team's next turn or only reads over. Kept in `<state>/server/budgets.json` and read at each turn.
package splice.server.budget

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.config.KnobWords
import splice.share.StateFile
import java.nio.file.Path

/** One budget: whose it is ([scope] and [name]), its limits (null: none that long), and its [action]. */
@Serializable
internal data class BudgetSetting(
    val scope: String,
    val name: String,
    @SerialName("daily_usd") val dailyUsd: Double? = null,
    @SerialName("monthly_usd") val monthlyUsd: Double? = null,
    val action: String = KnobWords.BUDGET_BLOCK,
)

@Serializable
internal data class BudgetsFile(val budgets: List<BudgetSetting> = emptyList())

internal class BudgetBook(
    /** `<state>/server/budgets.json`. Read here, once; a file that is there and cannot be read throws. */
    file: Path,
    private val io: CoroutineDispatcher,
) {
    private val store = StateFile(file, BudgetsFile.serializer())
    private val mutation = Mutex()

    @Volatile
    private var budgets: List<BudgetSetting> = store.read()?.budgets.orEmpty()

    /** Every budget, in the order they were first set. */
    fun all(): List<BudgetSetting> = budgets

    /** The budget of [name] in [scope], or null when none is set. */
    fun of(scope: SpendScope, name: String): BudgetSetting? =
        budgets.firstOrNull { it.scope == scope.wire && it.name == name }

    /** Sets [setting] in place of the one of its scope and name, and answers it. */
    suspend fun set(setting: BudgetSetting): BudgetSetting = mutation.withLock {
        val next = if (budgets.none { same(it, setting) }) {
            budgets + setting
        } else {
            budgets.map { if (same(it, setting)) setting else it }
        }
        withContext(io) { store.write(BudgetsFile(next)) }
        budgets = next
        setting
    }

    private fun same(a: BudgetSetting, b: BudgetSetting): Boolean = a.scope == b.scope && a.name == b.name
}
