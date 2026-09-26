// NEW: V4-133, FEATURES.md §5/§6 — GET/PUT /api/budgets. "spend budgets per head and per day with
// warn and block", one of the table-stakes items the operator kept in.
//
// THE FILE. One JSON document, `budgets.json` under the state dir, written whole through
// SecureFile's temp-then-atomic-move (0600 — a daily USD ceiling is the operator's own spend
// policy, not public), the same shape splice.sessions.teams.TeamStore already uses for a small
// operator-edited list: a backup copy taken first, reads served from a memory cache and re-read
// only when the file's mtime moves (a hand edit is seen without a restart). Unlike TeamStore this
// route is a WHOLE-SET REPLACE (the console always PUTs the complete list it wants), so there is
// no delta to protect: a read degrades to empty on a file that will not parse (GET must always
// answer something, and names why beside it; the daemon's log says it once per version, V4-296) and
// a write always lands the validated set handed to it, which is the recovery path for that same
// broken file, not a second way to lose data.
//
// NO BUDGET IS THE STATE EVERY HEAD STARTS IN. [Budget.dailyUsd] null is not zero: a head with a
// zero-dollar budget would block its first turn, so "no row for this head" is what "unbudgeted"
// means, and the list may be empty forever without that being a defect.
package splice.usage.budgets

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** The state-dir file the budgets live in. */
public const val BUDGETS_FILE: String = "budgets.json"

/** One head's daily spend budget. Wire-identical to `console/src/entities/budget/model/types.ts`. */
@Serializable
public data class Budget(
    val head: String,
    /** USD per day, or null for "no budget" — see the file header. */
    @SerialName("daily_usd") val dailyUsd: Double? = null,
    /** "warn" or "block", validated by [BudgetStore.replace]; never a third spelling on disk. */
    val action: String,
)

@Serializable
private data class BudgetsDocument(val budgets: List<Budget> = emptyList())

/** A write [BudgetStore] refused, with the reason the route reports as a 400. */
public class BudgetRefusal(message: String) : IllegalArgumentException(message)

/** The actions a budget may name. [BudgetStore.replace] refuses any other spelling. */
public object BudgetActions {
    public const val WARN: String = "warn"
    public const val BLOCK: String = "block"
    public val VALID: Set<String> = setOf(WARN, BLOCK)
}

/** The budgets as read, and why there are none when the file does not parse ([unreadable], else null). */
public data class BudgetsRead(val budgets: List<Budget>, val unreadable: String?)

public class BudgetStore(
    private val file: Path,
    /** Where a file that does not parse is said; the daemon's log unless the caller catches it. */
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private var cached: List<Budget> = emptyList()
    private var cachedStamp: Long? = null

    /** The stamp of the version that does not parse whose line was logged, so each is said once. */
    private var saidStamp: Long? = null

    /** Every budget, in the order the operator last saved them. */
    @Synchronized
    public fun budgets(): List<Budget> = read().budgets

    /** [budgets], with the reason there are none when the file does not parse. Enforcement then runs
     *  every head with no budget, so the first read of each such version logs it (V4-296); GET
     *  /api/budgets answers the reason beside the empty list. */
    @Synchronized
    public fun read(): BudgetsRead = load().fold(
        onSuccess = { BudgetsRead(it, null) },
        onFailure = { failure -> BudgetsRead(emptyList(), unreadable(failure)) },
    )

    private fun unreadable(failure: Throwable): String {
        val why = "$file could not be read (${SafeFailureText.render(failure)}); every head runs with no " +
            "budget, block and warn alike, until it parses again or PUT /api/budgets replaces it"
        val stamp = stamp()
        if (stamp != saidStamp) {
            saidStamp = stamp
            log("[budget] ${LogSafe.str(why)}\n")
        }
        return why
    }

    /** Replaces the whole set. One row per head: a duplicate head in [budgets] is refused rather
     *  than silently keeping the last one, which would discard the operator's edit with no trace. */
    @Synchronized
    public fun replace(budgets: List<Budget>): List<Budget> {
        validate(budgets)
        write(budgets)
        return budgets
    }

    // Split one row at a time (ThrowsCount: max 2 throws per function) — each check named rather
    // than folded into one wide function with four.
    // Each reason is printed by the console under the row it refused (V4-220): UI words, one sentence,
    // and never the head the row already names.
    private fun validate(budgets: List<Budget>) {
        budgets.forEach { budget ->
            requireHead(budget)
            requireValidAction(budget)
            requireNonNegativeBudget(budget)
        }
        requireNoDuplicateHeads(budgets)
    }

    private fun requireHead(budget: Budget) {
        if (budget.head.isBlank()) throw BudgetRefusal("Every budget needs a head.")
    }

    private fun requireValidAction(budget: Budget) {
        if (budget.action in BudgetActions.VALID) return
        val allowed = BudgetActions.VALID.joinToString(" or ")
        throw BudgetRefusal("The action must be $allowed, not '${budget.action}'.")
    }

    private fun requireNonNegativeBudget(budget: Budget) {
        val dailyUsd = budget.dailyUsd
        if (dailyUsd != null && dailyUsd < 0) throw BudgetRefusal("The daily cap can't be negative.")
    }

    private fun requireNoDuplicateHeads(budgets: List<Budget>) {
        val duplicates = budgets.groupBy { it.head }.filterValues { it.size > 1 }.keys
        if (duplicates.isEmpty()) return
        val repeated = duplicates.sorted().joinToString()
        throw BudgetRefusal("Each head can have only one budget (more than one for $repeated).")
    }

    private fun write(budgets: List<Budget>) {
        if (Files.exists(file)) {
            SecureFile.writeAtomic0600(file.resolveSibling("${file.fileName}.bak"), Files.readString(file))
        }
        SecureFile.writeAtomic0600(file, json.encodeToString(BudgetsDocument.serializer(), BudgetsDocument(budgets)))
        cached = budgets
        cachedStamp = stamp()
    }

    /** The current budgets, or a refusal when the file exists and does not parse — never silently
     *  replaced by an empty list, the same law splice.sessions.teams.TeamStore keeps. */
    private fun load(): Result<List<Budget>> {
        val now = stamp()
        if (now == null) return Result.success(emptyList())
        if (now == cachedStamp) return Result.success(cached)
        return Cancellables.runCatchingCancellable {
            json.decodeFromString(BudgetsDocument.serializer(), Files.readString(file)).budgets
        }.onSuccess {
            cached = it
            cachedStamp = now
        }
    }

    private fun stamp(): Long? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- no file yet is the empty store, not a failure
        Cancellables.runCatchingCancellable { Files.getLastModifiedTime(file).toMillis() }.getOrNull()
}
