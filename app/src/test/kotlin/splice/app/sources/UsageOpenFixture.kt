// Production quota stores and parser over synthetic state, with no credentials or provider traffic.
package splice.app.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.usage.QuotaSnapshot
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.usage.UsageHead
import splice.usage.UsageHeads
import splice.usage.perf.PerfRowsSource
import splice.usage.quota.QuotaClocks
import splice.usage.quota.QuotaPoller
import splice.usage.quota.QuotaProbe
import splice.usage.quota.QuotaSnapshotSink
import splice.usage.quota.UsagePayloads
import java.nio.file.Files
import java.nio.file.Path

internal class UsageOpenFixture(dir: Path, scope: CoroutineScope) {
    internal var now = SCALE_SINCE + 7L * 24 * 60 * 60 * 1_000
    internal val history = SyntheticPerfHistory(dir).apply { create() }
    internal val rateFile = dir.resolve("codex-ratelimit.json")
    internal val trackerFile = dir.resolve("codex-quota.json")
    private val usageFile = dir.resolve("codex-usage.json")
    internal val readPaths = setOf(
        usageFile,
        rateFile,
        trackerFile,
        history.file,
        history.file.resolveSibling("${history.file.fileName}.1"),
    )
    private val store = createStore()
    private val tracker = QuotaTracker(trackerFile, clock = { now })
    private val type = Class.forName("splice.usage.quota.CodexQuotaParser")
    private val parser = type.getDeclaredConstructor().newInstance()
    private val parse = type.getDeclaredMethod("parse", JsonObject::class.java, Long::class.javaPrimitiveType)
    private val thread = Thread.currentThread().threadId()
    internal var probes = 0
        private set
    internal var elapsed = 0L
    internal var historyReads = 0
        private set
    internal val perf = PerfRowsFileSource(history.file)
    private val poller = QuotaPoller(
        scope,
        "synthetic",
        QuotaProbe(::probe),
        QuotaSnapshotSink(tracker::record),
        { },
        clocks = QuotaClocks(wall = WallClock { now }, elapsed = ElapsedClock { elapsed }),
    )
    private val heads = UsageHeads {
        listOf(
            UsageHead(
                "synthetic",
                "claude-synthetic",
                UsageStoreSource(store, tracker, listOf(poller)),
                80,
                0,
                perfRows = PerfRowsSource { cutoff ->
                    historyReads++
                    perf.window(cutoff)
                },
            ),
        )
    }
    internal val payloads = UsagePayloads(heads, ConfigService(StatePaths(baseOverride = dir)), WallClock { now })

    private fun probe(): QuotaSnapshot {
        assertEquals(thread, Thread.currentThread().threadId(), "all probe work stays on the measured thread")
        probes++
        val body = """{"plan_type":"synthetic","rate_limit":{"primary_window":{"used_percent":25,"limit_window_seconds":18000,"reset_after_seconds":3600}}}"""
        return requireNotNull(parse.invoke(parser, Json.parseToJsonElement(body).jsonObject, now) as? QuotaSnapshot)
    }

    private fun createStore(): UsageStore {
        val buckets = linkedMapOf<Long, Pair<Long, Long>>()
        repeat(SCALE_REQUESTS) { index ->
            val ts = SCALE_SINCE + index * (7L * 24 * 60 * 60 * 1_000) / SCALE_REQUESTS
            if (ts > now - 5L * 60 * 60 * 1_000) {
                val minute = ts / 60_000 * 60_000
                buckets[minute] = ts to ((buckets[minute]?.second ?: 0) + 64)
            }
        }
        Files.writeString(
            usageFile,
            buckets.values.joinToString(",", "[", "]") { (ts, tokens) ->
                """{"timestamp":$ts,"output_tokens":$tokens}"""
            },
        )
        Files.writeString(
            rateFile,
            """{"limit_tokens":5000,"remaining_tokens":4000,"reset_tokens":"1h","updated_at":$now}""",
        )
        return UsageStore(usageFile, rateFile, clock = { now })
    }
}
