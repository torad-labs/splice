package splice.app.instrumentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
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
import splice.core.util.MonoClock
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

internal enum class BenchmarkRetainedCursorOutcome {
    COMPLETE,
    EXPECTED_RETENTION_INCOMPLETE,
    REFUSED,
}

internal data class BenchmarkRetainedCursorRead(
    val status: String?,
    val issueCodes: List<String>?,
    val page: BenchmarkRetainedCursorPage,
    val coverage: BenchmarkRetainedCursorCoverage,
)

internal data class BenchmarkRetainedCursorPage(
    val returnedObservations: Int?,
    val knownMatchingEvents: Long?,
    val totalMatchingEvents: Long?,
    val malformedRows: Long?,
    val hasNext: Boolean?,
)

internal data class BenchmarkRetainedCursorCoverage(
    val status: String?,
    val historicalCompleteness: String?,
)

internal fun classifyBenchmarkRetainedCursor(read: BenchmarkRetainedCursorRead): BenchmarkRetainedCursorOutcome {
    if (!validRetainedCursorRead(read)) {
        return BenchmarkRetainedCursorOutcome.REFUSED
    }
    return classifyValidRetainedCursor(read)
}

private fun classifyValidRetainedCursor(read: BenchmarkRetainedCursorRead): BenchmarkRetainedCursorOutcome {
    val issues = checkNotNull(read.issueCodes)
    return when {
        isCompleteRetainedCursor(read, issues) -> BenchmarkRetainedCursorOutcome.COMPLETE
        isExpectedIncompleteRetainedCursor(read, issues) ->
            BenchmarkRetainedCursorOutcome.EXPECTED_RETENTION_INCOMPLETE
        else -> BenchmarkRetainedCursorOutcome.REFUSED
    }
}

private fun isCompleteRetainedCursor(read: BenchmarkRetainedCursorRead, issues: List<String>): Boolean =
    listOf(
        read.status == "complete",
        issues.isEmpty(),
        read.page.totalMatchingEvents == read.page.knownMatchingEvents,
        read.coverage.status == "retained_snapshot",
        read.coverage.historicalCompleteness == UNKNOWN_RETAINED_HISTORY,
    ).all { it }

private fun isExpectedIncompleteRetainedCursor(
    read: BenchmarkRetainedCursorRead,
    issues: List<String>,
): Boolean = listOf(
    read.status == "incomplete",
    issues.isNotEmpty(),
    issues.all { it in EXPECTED_RETAINED_RETENTION_ISSUES },
    read.page.totalMatchingEvents == null,
    read.coverage.status == "incomplete",
    read.coverage.historicalCompleteness == UNKNOWN_RETAINED_HISTORY,
).all { it }

private fun validRetainedCursorRead(read: BenchmarkRetainedCursorRead): Boolean {
    val requiredValues = listOf(
        read.issueCodes,
        read.page.returnedObservations,
        read.page.knownMatchingEvents,
        read.page.malformedRows,
    )
    if (requiredValues.any { it == null }) return false
    val issues = checkNotNull(read.issueCodes)
    return validRetainedIssueCodes(issues) &&
        validRetainedPageCounts(
            checkNotNull(read.page.returnedObservations),
            checkNotNull(read.page.knownMatchingEvents),
            checkNotNull(read.page.malformedRows),
            read.page.hasNext,
        )
}

private fun validRetainedIssueCodes(issues: List<String>): Boolean =
    issues.size <= MAX_RETAINED_CURSOR_ISSUES && issues.distinct().size == issues.size &&
        issues.all { code ->
            code.isNotEmpty() && code.length <= MAX_RETAINED_ISSUE_CODE_LENGTH &&
                code.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        }

private fun validRetainedPageCounts(
    returned: Int,
    known: Long,
    malformed: Long,
    hasNext: Boolean?,
): Boolean {
    if (returned !in 1..RETAINED_CURSOR_PAGE_SIZE) return false
    if (known < returned.toLong() || malformed != 0L) return false
    return hasNext == (known > returned.toLong())
}

private const val RETAINED_CURSOR_PAGE_SIZE = 32
private const val MAX_RETAINED_CURSOR_ISSUES = 8
private const val MAX_RETAINED_ISSUE_CODE_LENGTH = 64
private const val UNKNOWN_RETAINED_HISTORY = "unknown_before_retained_segments"
private val RETAINED_CURSOR_STATUSES = setOf("complete", "incomplete", "invalid", "busy", "expired")
private val RETAINED_CURSOR_COVERAGE_STATUSES = setOf("retained_snapshot", "incomplete", "unknown")
private val EXPECTED_RETAINED_RETENTION_ISSUES = setOf(
    "evidence_prior_loss_unknown",
    "evidence_retention_expired",
)

internal fun isUsableRetainedHistorySeries(series: JsonArray?): Boolean =
    if (series.isNullOrEmpty()) {
        false
    } else {
        series.all { value ->
            val point = value as? JsonObject ?: return@all false
            val from = JsonScalars.long(point, "from_ms") ?: return@all false
            val until = JsonScalars.long(point, "until_ms") ?: return@all false
            hasValidRetainedPointShape(point, from, until)
        }
    }

private fun hasValidRetainedPointShape(point: JsonObject, from: Long, until: Long): Boolean =
    from < until && point["scope"] is JsonObject && point["metrics"] is JsonObject &&
        point["coverage"] is JsonObject

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
    private var expectedRetentionIncomplete = 0L
    private var historySeriesSizes = emptyList<Int?>()
    private var cursorStatus: String? = null
    private var cursorIssueCodes = emptyList<String>()
    private var cursorReturnedObservations: Int? = null
    private var cursorKnownMatchingEvents: Long? = null
    private var cursorTotalMatchingEvents: Long? = null
    private var cursorMalformedRows: Long? = null
    private var cursorHasNext: Boolean? = null
    private var cursorCoverageStatus: String? = null
    private var cursorHistoricalCompleteness: String? = null
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
        val waitStarted = MonoClock.nowMs()
        check(AsyncFileIo.awaitFile(paths.perfAggregatesDir.resolve("checkpoint.json"))) {
            checkpointFailure(MonoClock.nowMs() - waitStarted)
        }
        check(checkNotNull(evidence).checkpointCoverage())
        check(AsyncFileIo.awaitDirectory(paths.perfEvidenceDir))
        alignSyntheticFileClock()
    }

    private fun checkpointFailure(waitElapsed: Long): String {
        val pending = AsyncFileIo.pendingUnder(paths.perfAggregatesDir)
        val checkpoint = paths.perfAggregatesDir.resolve("checkpoint.json").toAbsolutePath().normalize()
        return buildJsonObject {
            put("code", "retained_checkpoint_settlement_failed")
            put("completed_hours", producedHours)
            put("virtual_until_ms", now)
            put("await_elapsed_ms", waitElapsed)
            put("interrupted", Thread.currentThread().isInterrupted)
            put("global_pending_tasks", pending.count)
            put("aggregate_pending_path_count", pending.paths.size)
            put("checkpoint_pending", checkpoint in pending.paths)
            put("io", Json.encodeToJsonElement(AsyncFileIo.workSnapshot))
            put("aggregate", Json.encodeToJsonElement(checkNotNull(aggregate).status()))
            put("evidence", Json.encodeToJsonElement(checkNotNull(writer).sink.status()))
            put("coverage", Json.encodeToJsonElement(checkNotNull(evidence).coverageSink.status()))
            put("accumulator", Json.encodeToJsonElement(checkNotNull(accumulator).status()))
        }.toString()
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
        val sizes = mutableListOf<Int?>()
        repeat(2) {
            val result = query.series(historyQuery)
            try {
                queryCount++
                val series = result["series"] as? JsonArray
                val size = series?.size
                sizes += size
                if (!isUsableRetainedHistorySeries(series)) queryRefused++
            } finally { PerfWorkBudget.release(result) }
        }
        historySeriesSizes = sizes.toList()
        exerciseCursorRead()
    }

    private fun exerciseCursorRead() {
        val request = PerfEvidenceCursorRequest(
            PerfEvidenceQuery(now - RETAINED_HOUR_MS, now, HEAD),
            pageSize = RETAINED_CURSOR_PAGE_SIZE,
        )
        checkNotNull(source).page(request).use { page ->
            cursorCount++
            val read = cursorRead(page.observations.size, page.next != null, page.metadata)
            recordCursorDiagnostics(read)
            val outcome = classifyBenchmarkRetainedCursor(read)
            if (outcome == BenchmarkRetainedCursorOutcome.EXPECTED_RETENTION_INCOMPLETE) {
                expectedRetentionIncomplete++
            }
            if (outcome == BenchmarkRetainedCursorOutcome.REFUSED) {
                queryRefused++
            }
        }
    }

    private fun cursorRead(returned: Int, hasNext: Boolean, metadata: JsonObject): BenchmarkRetainedCursorRead {
        val issueCodes = cursorIssueCodes(metadata)
        val coverage = metadata["coverage"] as? JsonObject
        return BenchmarkRetainedCursorRead(
            status = JsonScalars.str(metadata, "status"),
            issueCodes = issueCodes,
            page = BenchmarkRetainedCursorPage(
                returned,
                JsonScalars.long(metadata, "known_matching_events"),
                JsonScalars.long(metadata, "total_matching_events"),
                JsonScalars.long(metadata, "malformed_rows"),
                hasNext,
            ),
            coverage = BenchmarkRetainedCursorCoverage(
                coverage?.let { JsonScalars.str(it, "status") },
                coverage?.let { JsonScalars.str(it, "historical_completeness") },
            ),
        )
    }

    private fun recordCursorDiagnostics(read: BenchmarkRetainedCursorRead) {
        cursorStatus = read.status?.takeIf { it in RETAINED_CURSOR_STATUSES } ?: "unknown"
        cursorIssueCodes = read.issueCodes ?: listOf("invalid_issue_metadata")
        cursorReturnedObservations = read.page.returnedObservations
        cursorKnownMatchingEvents = read.page.knownMatchingEvents
        cursorTotalMatchingEvents = read.page.totalMatchingEvents
        cursorMalformedRows = read.page.malformedRows
        cursorHasNext = read.page.hasNext
        cursorCoverageStatus = read.coverage.status?.takeIf { it in RETAINED_CURSOR_COVERAGE_STATUSES } ?: "unknown"
        cursorHistoricalCompleteness = read.coverage.historicalCompleteness?.let {
            if (it == UNKNOWN_RETAINED_HISTORY) it else "unknown"
        }
    }

    private fun cursorIssueCodes(metadata: JsonObject): List<String>? {
        val values = metadata["read_errors"] as? JsonArray
        val boundedValues = values?.takeIf { it.size <= MAX_RETAINED_CURSOR_ISSUES } ?: return null
        val codes = boundedValues.map { cursorIssueCode(it) ?: return null }
        return codes.distinct().takeIf { it.size == boundedValues.size }
    }

    private fun cursorIssueCode(value: JsonElement): String? {
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val code = primitive.content
        return code.takeIf {
            it.isNotEmpty() && it.length <= MAX_RETAINED_ISSUE_CODE_LENGTH &&
                it.all { character -> character in 'a'..'z' || character in '0'..'9' || character == '_' }
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
        put("expected_retention_incomplete", expectedRetentionIncomplete)
        put("cursor_count", cursorCount)
        put("read_diagnostics", readDiagnostics())
        put("sinks", Json.encodeToJsonElement(statuses()))
        put("aggregate_costs", aggregate?.costs?.snapshot()?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
        put("evidence_costs", source?.costs?.snapshot()?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
    }

    private fun readDiagnostics() = buildJsonObject {
        putAll(historyDiagnostics())
        putAll(cursorDiagnostics())
    }

    private fun historyDiagnostics() = buildJsonObject {
        put(
            "history_series_sizes",
            buildJsonArray { historySeriesSizes.forEach { add(nullableJson(it)) } },
        )
    }

    private fun cursorDiagnostics() = buildJsonObject {
        put("cursor_status", nullableJson(cursorStatus))
        put("cursor_issue_codes", buildJsonArray { cursorIssueCodes.forEach { add(it) } })
        put("cursor_returned_observations", nullableJson(cursorReturnedObservations))
        put("cursor_known_matching_events", nullableJson(cursorKnownMatchingEvents))
        put("cursor_total_matching_events", nullableJson(cursorTotalMatchingEvents))
        put("cursor_malformed_rows", nullableJson(cursorMalformedRows))
        put("cursor_has_next", nullableJson(cursorHasNext))
        put("cursor_coverage_status", nullableJson(cursorCoverageStatus))
        put("cursor_historical_completeness", nullableJson(cursorHistoricalCompleteness))
    }

    private fun nullableJson(value: Int?) = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullableJson(value: Long?) = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullableJson(value: Boolean?) = value?.let(::JsonPrimitive) ?: JsonNull
    private fun nullableJson(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

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
