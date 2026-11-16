// NEW: server mode (docs/specs/server-mode.md, F5, ledger-export). The ledger copied out to `[server.export] to`, as
// one JSONL file per UTC hour, `splice-usage/YYYY/MM/DD/HH.jsonl` under the target, each line a ledger row as the
// ledger writes it. Each hour is written whole, so writing it again holds each row once. The timed export leaves an
// hour whose rows have not changed since this daemon last wrote it as it is; `--now` writes every hour, so it also puts
// back a file someone removed from the target. It runs on its own every `every` once the daemon opens,
// and at once when the operator asks (`splice server usage export --now`); the two never overlap.
package splice.server.export

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.topology.ServerExport
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.server.ServerAnswer
import splice.server.ledger.UsageLedger
import splice.upstream.LifecycleScope
import splice.upstream.Ticker
import splice.upstream.Waiter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** How one export went: the rows and hours the ledger holds, the hours written this time, and why it stopped. */
private data class ExportDone(val rows: Int, val hours: Int, val written: Int, val why: String? = null)

/** What the export runs on: blocking writes, the clock a bucket request is dated by, and the waits between tries. */
internal data class ExportClock(val io: CoroutineDispatcher, val clock: Clock, val waiter: Waiter, val ticker: Ticker)

public class UsageExport internal constructor(
    private val config: ServerExport,
    private val ledger: UsageLedger,
    private val runs: ExportClock,
    private val log: LogSink,
) {
    private val scope = LifecycleScope(runs.io)
    private val exporting = Mutex()

    // The rows each hour's file held when this daemon last wrote it; the ledger only grows, so a count is enough.
    private val written = ConcurrentHashMap<String, Int>()

    @Volatile
    private var target: ExportTarget? = null

    /** Starts the export, reading a bucket's key from [env] at each one; nothing when `[server.export]` is not set. */
    internal fun open(env: EnvReader) {
        if (!config.declared) return
        val names = BucketKeyNames(config.accessKeyEnv.orEmpty(), config.secretKeyEnv.orEmpty())
        target = ExportTargets(runs.io).of(config, BucketReach(names, env, runs.clock, runs.waiter))
        val every = config.everyMs ?: return
        scope.launch { while (runs.ticker.awaitTick(every)) tick() }
    }

    /** `splice server usage export --now`: exports at once, and says how it went. */
    public suspend fun now(): ServerAnswer {
        val to = target ?: return ServerAnswer.Refused(HttpStatusCode.Conflict, NOT_SET)
        val done = export(to, everyHour = true)
        val failed = done.why?.let { "the export to ${config.to} failed: $it" }
        if (failed != null) return ServerAnswer.Refused(HttpStatusCode.BadGateway, failed)
        val body = buildJsonObject {
            put("to", config.to)
            put("rows", done.rows)
            put("hours", done.hours)
            put("written", done.written)
        }
        return ServerAnswer.Done(HttpStatusCode.OK, body)
    }

    /** Stops the export; one under way is let finish its file. */
    internal suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
    }

    /** One export on its own schedule; what stops it is logged, and the next one tries again. */
    private suspend fun tick() {
        val to = target ?: return
        val why = Cancellables.runCatchingCancellable { export(to, everyHour = false).why }
            .getOrElse(SafeFailureText::render)
        why?.let { log("[server] usage export to ${LogSafe.str(config.to)} failed: ${LogSafe.str(it)}\n") }
    }

    /** Writes each hour, oldest first, stopping at a failure; unless [everyHour], only those whose rows changed since
     *  this daemon last wrote them. */
    private suspend fun export(to: ExportTarget, everyHour: Boolean): ExportDone = exporting.withLock {
        val rows = ledger.all()
        val hours = rows.groupBy { HOUR_FILE.format(Instant.parse(it.at)) }.toSortedMap()
        var wrote = 0
        for ((key, inHour) in hours) {
            if (!everyHour && written[key] == inHour.size) continue
            val bytes = inHour.joinToString("") { ledger.line(it) + "\n" }.toByteArray()
            val why = to.put(key, bytes)
            if (why != null) return@withLock ExportDone(rows.size, hours.size, wrote, why)
            written[key] = inHour.size
            wrote++
        }
        ExportDone(rows.size, hours.size, wrote)
    }
}

// Each row's file under the target: the UTC hour it arrived in.
private val HOUR_FILE = DateTimeFormatter.ofPattern("'splice-usage/'yyyy/MM/dd/HH'.jsonl'").withZone(ZoneOffset.UTC)

private const val NOT_SET = "this server exports nowhere: splice.toml's [server] has no [server.export] with a `to`"
