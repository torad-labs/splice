package splice.app.instrumentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import splice.app.sources.observability.PerfAggregateStore
import splice.app.sources.observability.PerfEvidenceFileSource
import splice.core.config.StatePaths
import splice.core.perf.observation.NoPerfObserver
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservationBudget
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfSinkStatus
import splice.core.perf.observation.aggregate.PerfAccumulator
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.head.perf.RequestEvidenceFiles
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.history.PerfEvidenceCursorRequest
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/** Actual owners with a fixed shared-pool reservation representing other app work; not owner heap attribution. */
internal class BenchmarkRetainedOwners(
    private val paths: StatePaths,
    private val enabled: Boolean,
    start: Long,
    sourceCommit: String,
) : AutoCloseable {
    val identity = PerfIdentity("retained-fixture", "source:$sourceCommit", "fixture-v1", "retained-config-v1")
    private var now = start
    private var producedHours = 0
    private val wall = WallClock { now }
    private val elapsed = ElapsedClock { now }
    private val composition = checkNotNull(PerfWorkBudget.acquire(COMPOSITION_BYTES))
    private val accumulator = if (enabled) PerfAccumulator(identity) else null
    private val aggregate = if (enabled) PerfAggregateStore(paths.perfAggregatesDir, identity, wall) else null
    private val evidence = if (enabled) {
        RequestEvidenceFiles(
            paths.perfEvidenceDir,
            clock = wall,
            elapsed = elapsed,
        )
    } else {
        null
    }
    private val writer = evidence?.forHead(HEAD)
    private val source = if (enabled) PerfEvidenceFileSource(paths.perfEvidenceDir, wall) else null
    private var queryCount = 0L
    private var queryRefused = 0L
    private var cursorCount = 0L
    private val query = PerfQueryService(
        object : PerfQuerySources {
            override fun heads(): Set<String> = setOf(HEAD)
            override fun rows(head: String): PerfRowsSource? = null
            override fun history() = aggregate
            override fun evidence() = source
        },
        clock = wall,
    )
    private val recorder = PerfRecorder(identity, observer(), elapsed, wall)

    init {
        aggregate?.let { check(it.openWriter()) }
        evidence?.let { check(it.startCoverage(HEAD, identity.bootId)) }
    }

    private fun observer(): PerfObserver = if (!enabled) {
        NoPerfObserver
    } else {
        PerfObserver { event ->
            val recorded = checkNotNull(accumulator).record(event)
            val durable = checkNotNull(writer).record(event)
            recorded && durable
        }
    }

    fun advance(hours: Int) {
        check(producedHours + hours <= MAX_HOURS)
        repeat(hours) {
            val from = now
            repeat(60) { minute() }
            checkpoint(from)
            producedHours++
        }
        if (enabled) exerciseReads()
    }

    private fun minute() {
        val from = now
        val request = recorder.request(PerfScope(head = HEAD, model = "fixture", transport = "sse"), now, now)
        now += 20
        check(request.finish("good"))
        now = from + 60_000L - 1
        check(recorder.emit(PerfScope(head = HEAD), PerfEventKind.CHECKPOINT))
        now = from + 60_000L
        if (enabled) check(AsyncFileIo.awaitDirectory(paths.perfEvidenceDir))
    }

    private fun checkpoint(from: Long) {
        if (!enabled) return
        val snapshot = checkNotNull(accumulator).snapshot()
        val completed = snapshot.copy(
            fromMs = from,
            points = snapshot.points.filter { it.fromMs >= from && it.fromMs < now },
            coverage = snapshot.coverage.copy(
                requestedFromMs = from,
                observedMs = minOf(RETAINED_HOUR_MS, snapshot.coverage.observedMs),
            ),
        )
        check(checkNotNull(aggregate).append(completed))
        check(AsyncFileIo.awaitFile(paths.perfAggregatesDir.resolve("checkpoint.json")))
        check(checkNotNull(evidence).checkpointCoverage())
        check(AsyncFileIo.awaitDirectory(paths.perfEvidenceDir))
        alignSyntheticFileClock()
    }

    private fun alignSyntheticFileClock() {
        // Production evidence retention uses mtime. Align only newly written synthetic segments to its injected clock.
        // Existing payloads, prefix hashes and coverage ledgers are never rewritten by this fixture.
        Files.newDirectoryStream(paths.perfEvidenceDir, "*.jsonl").use { files ->
            files.forEach { file ->
                if (Files.getLastModifiedTime(
                        file,
                    ).toMillis() > now
                ) {
                    Files.setLastModifiedTime(file, FileTime.fromMillis(now))
                }
            }
        }
    }

    private fun exerciseReads() {
        val historyQuery = PerfQuery(now - 24 * RETAINED_HOUR_MS, now, source = "history")
        repeat(2) {
            val result = query.series(historyQuery)
            try {
                queryCount++
                if ((result["series"]?.jsonArray?.size ?: 0) == 0) queryRefused++
            } finally { PerfWorkBudget.release(result) }
        }
        checkNotNull(
            source,
        ).page(
            PerfEvidenceCursorRequest(PerfEvidenceQuery(now - RETAINED_HOUR_MS, now, HEAD), pageSize = 32),
        ).use { page ->
            cursorCount++
            val status = JsonScalars.str(page.metadata, "status")
            if (status !in setOf("ok", "partial")) queryRefused++
        }
    }

    fun status() = buildJsonObject {
        val budget = PerfObservationBudget.status()
        put("mode", if (enabled) "local" else "minimal")
        put("virtual_ms", now)
        put("produced_hours", producedHours)
        put("requests", producedHours * 60L)
        put("observation_reserved_bytes", budget.reservedBytes)
        put("observation_limit_bytes", budget.totalLimitBytes)
        put("work_leased_bytes", PerfWorkBudget.retainedBytes())
        put("work_limit_bytes", PerfWorkBudget.LIMIT_BYTES)
        put("composition_reserved_bytes", COMPOSITION_BYTES)
        put("query_count", queryCount)
        put("query_refused", queryRefused)
        put("cursor_count", cursorCount)
        put("sinks", Json.encodeToJsonElement(statuses()))
        put("aggregate_costs", aggregate?.costs?.snapshot()?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
        put("evidence_costs", source?.costs?.snapshot()?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
    }

    private fun statuses(): List<PerfSinkStatus> = listOfNotNull(
        accumulator?.status(),
        aggregate?.status(),
        writer?.sink?.status(),
        evidence?.coverageSink?.status(),
    )

    override fun close() {
        query.close()
        evidence?.let { check(it.stopCoverage(HEAD)); it.close() }
        aggregate?.close()
        accumulator?.close()
        composition.close()
    }
}

private const val HEAD = "bench"
private const val COMPOSITION_BYTES = 11L * 1_024 * 1_024 / 2
private const val MAX_HOURS = 1_200
