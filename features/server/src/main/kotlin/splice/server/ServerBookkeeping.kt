// NEW: server mode (docs/specs/server-mode.md, F5 and F12). What a server keeps of the turns it serves, built once
// beside its keys and people: the usage ledger every turn leaves its row in, what each person and team spent and the
// tokens each person's turns used in the last minute (both counted from those rows), their budgets, and the ledger's
// hourly export. ServerMode opens it with its listener and closes it after the listener, so the last turns' rows are
// written before the daemon goes.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.topology.ServerConfig
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.server.budget.BudgetApi
import splice.server.budget.BudgetBook
import splice.server.budget.BudgetGate
import splice.server.budget.ServerSpend
import splice.server.export.ExportClock
import splice.server.export.UsageExport
import splice.server.ledger.LedgerParts
import splice.server.ledger.RowWatch
import splice.server.ledger.TurnLedger
import splice.server.ledger.UsageApi
import splice.server.ledger.UsageLedger
import splice.server.metrics.ServerMetrics
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessTicker
import java.nio.file.Path
import java.time.Clock

/** Where and when the bookkeeping's work runs: blocking writes on [io], the time from [clock], an export's waits on
 *  [waiter], and what it says in the daemon's log. */
internal data class BookRun(val io: CoroutineDispatcher, val clock: Clock, val waiter: Waiter, val log: LogSink)

public class ServerBookkeeping internal constructor(
    /** `<state>/server`, where the ledger and the budgets are kept beside the keys and the people. */
    folder: Path,
    private val server: ServerConfig,
    private val people: ServerPeople,
    private val upstreams: UpstreamApi,
    private val run: BookRun,
) {
    // What each person and team spent, counted from every row of the ledger.
    private val spend = ServerSpend(run.clock)

    // Every turn's row, as the reports, the exports and the budgets read them.
    // The tokens of each person's turns that ended in the last minute, counted from the same rows (F12).
    private val tokens = MinuteTokens(run.clock)

    private val ledger = UsageLedger(
        folder.resolve(USAGE_LEDGER_FILE),
        run.io,
        RowWatch { row ->
            spend.counted(row)
            tokens.counted(row)
        },
    )

    private val budgetBook = BudgetBook(folder.resolve(SERVER_BUDGETS_FILE), run.io)
    private val budgets = BudgetGate(budgetBook, spend, people, server.teams, server.budgets)

    /** The ledger's rows and reports, as the CLI, the control plane and an admin's key ask them. */
    public val usageApi: UsageApi = UsageApi(ledger, people, server, run.clock, upstreams)

    /** Each person's and team's budgets, as the CLI and the control plane set and list them. */
    public val budgetApi: BudgetApi = BudgetApi(budgetBook, budgets, run.log)

    /** The ledger copied out hour by hour to `[server.export]`, on its own and when the CLI asks. */
    public val usageExport: UsageExport =
        UsageExport(server.export, ledger, ExportClock(run.io, run.clock, run.waiter, ProcessTicker()), run.log)

    @Volatile
    private var turns: TurnLedger? = null

    /** The books a listener serves from: a ledger for its turns, told on [reach]'s board how each went and counting
     *  each in [metrics] (F6), and the budgets each turn must be within. */
    internal fun open(reach: ServerReach, metrics: ServerMetrics): ServerBooks {
        val parts = LedgerParts(ledger, reach.served, people, server.teams, upstreams, run.clock, metrics)
        val opened = TurnLedger(parts, run.io, run.log)
        turns = opened
        return ServerBooks(opened, budgets, tokens)
    }

    /** Starts the export on its own schedule, with the bucket's key read from [env] at each one. */
    internal fun exporting(env: EnvReader) {
        usageExport.open(env)
    }

    /** Waits a moment for the rows still being written, then stops the export. */
    internal suspend fun close() {
        turns?.close()
        turns = null
        usageExport.close()
    }
}

private const val USAGE_LEDGER_FILE = "ledger.jsonl"
private const val SERVER_BUDGETS_FILE = "budgets.json"
