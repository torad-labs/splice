// NEW: adapters bridging the gateway's file stores to the control plane's read interfaces, so
// the dashboard reads the same on-disk truth the head writes (a DOWN head still shows state).
package splice.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.control.CompactView
import splice.control.HeadCompactSource
import splice.control.HeadPerfSource
import splice.control.HeadUsageSource
import splice.control.PerfRow
import splice.control.PerfRowsSource
import splice.control.QuotaView
import splice.control.QuotaWindowView
import splice.control.RateLimitView
import splice.control.UsageView
import splice.core.usage.QuotaSnapshot
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore
import java.nio.file.Files
import java.nio.file.Path

public class UsageStoreSource(
    private val store: UsageStore,
    private val quota: QuotaTracker? = null,
) : HeadUsageSource {
    override fun snapshot(): UsageView {
        val state = store.readState()
        val ratelimit = store.readRateLimit()?.let {
            RateLimitView(it.limitTokens, it.remainingTokens, it.resetTokens)
        }
        return UsageView(state.outputTokens5h, state.entries, ratelimit, quota?.snapshot()?.let(::quotaView))
    }

    private fun quotaView(snapshot: QuotaSnapshot): QuotaView = QuotaView(
        fiveHour = snapshot.fiveHour?.let { QuotaWindowView(it.usedPercent.toInt(), it.resetsAt) },
        sevenDay = snapshot.sevenDay?.let { QuotaWindowView(it.usedPercent.toInt(), it.resetsAt) },
        plan = snapshot.plan,
    )
}

public class CompactStatsSource(private val stats: CompactStats) : HeadCompactSource {
    override fun summary(tailN: Int): CompactView {
        val s = stats.read(tailN)
        val tail = s.tail.map { row -> row.mapValues { (_, v) -> v.toString() } }
        return CompactView(s.total, s.byOutcome, tail)
    }
}

public class PerfStatsSource(private val stats: PerfStats) : HeadPerfSource {
    override fun tailNumeric(n: Int): List<Map<String, Long>> = stats.tailNumeric(n)
}

/** v0.4.0 (FEATURES.md §3): the perf JSONL with its outcome tags, both generations (`<file>.1` is
 *  the rotated one, 64 MiB each ≈ 20 days here). A cheap `"ts":N` scan skips rows before [sinceMs]
 *  without parsing them; a malformed line is skipped, never fatal. */
public class PerfRowsFileSource(private val file: Path) : PerfRowsSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val tsField = Regex(""""ts":(\d+)""")

    override fun rowsSince(sinceMs: Long): List<PerfRow> =
        listOf(file.resolveSibling("${file.fileName}.1"), file)
            .flatMap { generation -> lines(generation).mapNotNull { line -> row(line, sinceMs) } }
            .sortedBy { it.ts }

    private fun lines(path: Path): List<String> = Cancellables
        .runCatchingCancellable { Files.newBufferedReader(path).useLines { it.toList() } }
        .getOrDefault(emptyList())

    private fun row(line: String, sinceMs: Long): PerfRow? {
        val ts = tsField.find(line)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it >= sinceMs } ?: return null
        val obj = Cancellables.runCatchingCancellable { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        return obj?.let {
            val fields = buildMap { it.forEach { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { n -> put(k, n) } } }
            PerfRow(ts, JsonScalars.str(it, "outcome") ?: "?", fields)
        }
    }
}
