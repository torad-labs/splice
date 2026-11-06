package splice.app.instrumentation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.sources.observability.PerfAggregateStore
import splice.app.sources.observability.PerfEvidenceFileSource
import splice.core.config.StatePaths
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfSinkStatus
import splice.core.perf.observation.PerfStamp
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.EnvReader
import splice.core.util.MonoClock
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.head.perf.RequestEvidenceFiles
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Offline production-writer fixture. Invoke the already compiled test entry only after benchmark authorization. */
object BenchmarkFixtureSeed {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "expected task directory, until epoch ms, source commit" }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        require(root.toString().startsWith("/tmp/splice-instrumentation-"))
        require(!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) { "fixture directory must be new" }
        val until = args[1].toLong()
        require(until > WEEK_MS && until % MINUTE_MS == 0L)
        require(args[2].matches(Regex("[a-f0-9]{40}")))
        Files.createDirectories(root)
        require(root.toRealPath() == root) { "fixture path must not traverse symlinks" }
        val paths = StatePaths(root.resolve("state"), EnvReader { null }, root.resolve("home"))
        val identity =
            PerfIdentity("benchmark-retained-fixture", "source:${args[2]}", "fixture-v1", "fixture-config-v1")
        write(paths, until, identity)
        receipt(root, paths, until, identity)
    }

    private fun write(paths: StatePaths, until: Long, identity: PerfIdentity) {
        var now = until - WEEK_MS
        val clock = WallClock { now }
        val elapsed = ElapsedClock { now }
        PerfAggregateStore(paths.perfAggregatesDir, identity, clock).use { aggregate ->
            check(aggregate.openWriter())
            RequestEvidenceFiles(paths.perfEvidenceDir, clock = clock, elapsed = elapsed).use { evidence ->
                check(evidence.startCoverage(HEAD, identity.bootId))
                val writer = evidence.forHead(HEAD)
                repeat((WEEK_MS / MINUTE_MS).toInt()) { index ->
                    val from = now
                    val links = PerfLinks(requestId = "fixture-request-$index")
                    check(writer.record(event(identity, links, PerfEventKind.REQUEST_ARRIVED, now)))
                    now += 20
                    check(writer.record(event(identity, links, PerfEventKind.REQUEST_FINISHED, now)))
                    check(writer.record(event(identity, links, PerfEventKind.USAGE_RECORDED, now)))
                    now = from + MINUTE_MS
                    // Both writers share one lane; settle evidence before admitting the aggregate checkpoint.
                    val evidenceWaitStarted = MonoClock.nowMs()
                    check(AsyncFileIo.awaitDirectory(paths.perfEvidenceDir)) {
                        barrierFailure("evidence", index, evidenceWaitStarted, aggregate.status(), writer.sink.status())
                    }
                    check(aggregate.append(snapshot(identity, from, now)))
                    val aggregateWaitStarted = MonoClock.nowMs()
                    check(AsyncFileIo.awaitFile(paths.perfAggregatesDir.resolve("checkpoint.json"))) {
                        barrierFailure(
                            "aggregate_checkpoint",
                            index,
                            aggregateWaitStarted,
                            aggregate.status(),
                            writer.sink.status(),
                        )
                    }
                    // Hour boundaries certify actual accepted producer prefixes, never event extents.
                    if ((index + 1) % 60 == 0) {
                        check(evidence.checkpointCoverage())
                        awaitCoverage(paths, evidence, aggregate, index, "hourly_coverage")
                    }
                }
                check(evidence.stopCoverage(HEAD))
                val lastIndex = (WEEK_MS / MINUTE_MS).toInt() - 1
                awaitCoverage(paths, evidence, aggregate, lastIndex, "final_coverage")
                check(PerfEvidenceFileSource(paths.perfEvidenceDir, clock).settledUntilMs(HEAD) == until)
            }
            check(aggregate.status().let { it.failed == 0L && it.dropped == 0L && it.pending == 0L })
        }
    }

    private fun awaitCoverage(
        paths: StatePaths,
        evidence: RequestEvidenceFiles,
        aggregate: PerfAggregateStore,
        index: Int,
        stage: String,
    ) {
        val started = MonoClock.nowMs()
        check(AsyncFileIo.awaitDirectory(paths.perfEvidenceDir)) {
            barrierFailure(stage, index, started, aggregate.status(), evidence.coverageSink.status())
        }
    }

    private fun barrierFailure(
        stage: String,
        index: Int,
        started: Long,
        aggregate: PerfSinkStatus,
        evidence: PerfSinkStatus,
    ): String = buildJsonObject {
        put("code", "fixture_barrier_failed")
        put("stage", stage)
        put("minute_index", index)
        put("barrier_elapsed_ms", MonoClock.nowMs() - started)
        put("interrupted", Thread.currentThread().isInterrupted)
        put("aggregate", sinkCounters(aggregate))
        put("evidence", sinkCounters(evidence))
        put("io", Json.encodeToJsonElement(AsyncFileIo.WorkSnapshot.serializer(), AsyncFileIo.workSnapshot))
    }.toString()

    private fun sinkCounters(status: PerfSinkStatus): JsonObject = buildJsonObject {
        put("observed", status.observed)
        put("enqueued", status.enqueued)
        put("written", status.written)
        put("failed", status.failed)
        put("dropped", status.dropped)
        put("pending", status.pending)
        put("oldest_pending_ms", status.oldestPendingMs)
    }

    private fun snapshot(identity: PerfIdentity, from: Long, until: Long): PerfAggregateSnapshot {
        val bounds = PerfMetricRegistry.durationBoundsMs()
        val metrics = listOf("arrival_to_terminal_ms", "arrival_to_first_meaningful_content_ms", "queue_wait_ms")
        val points = metrics.flatMap { metric ->
            List(16) { model ->
                PerfAggregatePoint(
                    metric, PerfScope(head = HEAD, model = "fixture-model-$model", transport = "sse"),
                    from, until, 1L, 20.0, 20.0, 20.0,
                    List(bounds.size + 1) { if (it == bounds.indexOfFirst { bound -> bound >= 20.0 }) 1L else 0L },
                )
            }
        }
        return PerfAggregateSnapshot(identity, from, until, points, PerfCoverage(from, until, until - from))
    }

    private fun event(identity: PerfIdentity, links: PerfLinks, kind: PerfEventKind, now: Long): PerfObservation =
        PerfObservation(
            PerfStamp("${links.requestId}-${kind.name}", identity, now, now),
            PerfScope(head = HEAD, model = "fixture-model", transport = "sse"),
            kind,
            values = when (kind) {
                PerfEventKind.REQUEST_FINISHED -> mapOf("arrival_to_terminal_ms" to 20.0)
                PerfEventKind.USAGE_RECORDED -> mapOf(
                    "input_tokens" to 15.0, "output_tokens" to 4.0, "cached_tokens" to 5.0,
                    "usage_cache_write_tokens" to 0.0, "usage_cache_write_1h_tokens" to 0.0,
                    "usage_observed_input" to 1.0, "usage_observed_output" to 1.0,
                    "usage_observed_cache_read" to 1.0, "usage_observed_cache_write" to 1.0,
                    "usage_observed_cache_write_1h" to 1.0,
                )
                PerfEventKind.REQUEST_ARRIVED,
                PerfEventKind.REQUEST_ADMITTED,
                PerfEventKind.ATTEMPT_STARTED,
                PerfEventKind.ATTEMPT_FINISHED,
                PerfEventKind.PROTOCOL_PROGRESS,
                PerfEventKind.CONTENT_PROGRESS,
                PerfEventKind.CLIENT_PROGRESS,
                PerfEventKind.GAP,
                PerfEventKind.GATE_STATE,
                PerfEventKind.TOOL_STARTED,
                PerfEventKind.TOOL_FINISHED,
                PerfEventKind.TOOL_PHASE,
                PerfEventKind.RUNTIME,
                PerfEventKind.SELF_COST,
                PerfEventKind.CHECKPOINT,
                -> emptyMap()
            },
            links = links,
            outcome = if (kind == PerfEventKind.REQUEST_FINISHED) "good" else null,
        )

    private fun receipt(root: Path, paths: StatePaths, until: Long, identity: PerfIdentity) {
        val value = buildJsonObject {
            put("schema_version", 1)
            put("fixture_kind", "production_writers_with_virtual_wall_and_elapsed_clocks")
            put("identity", Json.encodeToJsonElement(PerfIdentity.serializer(), identity))
            put("from_ms", until - WEEK_MS)
            put("until_ms", until)
            put("head", HEAD)
            put("aggregate_scope_count", 16)
            put("aggregate_observations_per_minute", 48)
            put("evidence_requests_per_minute", 1)
            put("population_note", "aggregate and raw evidence are distinct synthetic fixture populations")
            put("coverage_certified_until_ms", until)
            putJsonArray("producer_classes") {
                listOf(
                    BenchmarkFixtureSeed::class.java,
                    PerfAggregateStore::class.java,
                    RequestEvidenceFiles::class.java,
                ).forEach { type ->
                    val bytes = checkNotNull(
                        type.getResourceAsStream("/${type.name.replace('.', '/')}.class"),
                    ).use { it.readBytes() }
                    add(
                        buildJsonObject {
                            put("class", type.name)
                            put(
                                "sha256",
                                java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                            )
                        },
                    )
                }
            }
            putJsonArray("files") {
                Files.walk(paths.stateDir).use { files ->
                    files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted().forEach { file ->
                        add(
                            buildJsonObject {
                                put("path", root.relativize(file).toString())
                                put("bytes", Files.size(file))
                                put("sha256", sha256(file))
                            },
                        )
                    }
                }
            }
        }
        SecureFile.writeAtomic0600(root.resolve("fixture-receipt.json"), Json.encodeToString(value))
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(8_192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private const val HEAD = "bench"
private const val MINUTE_MS = 60_000L
private const val WEEK_MS = 7L * 24 * 60 * MINUTE_MS
