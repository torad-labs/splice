package splice.app.sources.observability.cursor

import splice.app.sources.observability.EVIDENCE_DEDUP_BYTES
import splice.app.sources.observability.PerfEvidenceInventory
import splice.app.sources.observability.PerfEvidenceSeen
import splice.app.sources.observability.PerfSourceCosts
import splice.app.sources.observability.evidenceHeadPattern
import splice.core.util.AsyncFileIo
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.history.PerfEvidenceCursorRequest
import splice.usage.perf.history.PerfEvidencePage
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Semaphore

internal class PerfEvidenceCursors(
    private val directory: Path,
    private val clock: WallClock,
    private val costs: PerfSourceCosts,
) {
    private val inventory = PerfEvidenceInventory(directory)
    private val files = PerfCursorFiles(directory.resolveSibling("${directory.fileName}-cursors"))
    private val queries = Semaphore(1)

    fun page(request: PerfEvidenceCursorRequest): PerfEvidencePage = when {
        !valid(request) -> failure(request, "invalid", "invalid_evidence_cursor")
        !queries.tryAcquire() -> failure(request, "busy", "cursor_busy")
        else -> admitted(request)
    }

    private fun admitted(request: PerfEvidenceCursorRequest): PerfEvidencePage {
        val lease = PerfWorkBudget.acquire(PERF_CURSOR_RESERVE_BYTES, PerfWorkBudget.Kind.EVIDENCE_READ)
        if (lease == null) {
            queries.release()
            return failure(request, "busy", "memory_busy")
        }
        return execute(request, lease)
    }

    private fun execute(request: PerfEvidenceCursorRequest, lease: PerfWorkBudget.Lease): PerfEvidencePage {
        val work = PerfCursorWork()
        val started = MonoClock.nowMs()
        costs.begin(request.cursor == null)
        var retained = false
        var scanned = 0L
        return try {
            val manifest = request.cursor?.let { files.load(it.substringBefore(':')) } ?: create(request, work)
            validate(manifest, request)
            val offset = request.cursor?.substringAfter(':')?.toLongOrNull() ?: 0L
            require(offset in 0..manifest.matching) { "cursor_offset_invalid" }
            val reader = PerfEvidencePageRead(manifest, request, inventory, work)
            val page = reader.read(files, offset)
            scanned = if (request.cursor == null) manifest.scannedRows else page.observations.size.toLong()
            lease.retain(page, reader.retainedBytes)
            retained = true
            page
        } catch (failure: PerfReadRefusal) {
            failed(request, failure.issue)
        } catch (_: IOException) {
            failure(request, "expired", "cursor_read_error")
        } catch (_: IllegalArgumentException) {
            failure(request, "invalid", "cursor_invalid_or_changed")
        } finally {
            if (!retained) lease.close()
            costs.end(scanned, work.bytes, (MonoClock.nowMs() - started).coerceAtLeast(0L))
            queries.release()
        }
    }

    private fun failed(request: PerfEvidenceCursorRequest, issue: PerfReadIssue): PerfEvidencePage {
        val reason = issue.code
        val status = if (reason.endsWith("_limit")) "incomplete" else "expired"
        return failure(request, status, reason)
    }

    private fun create(
        request: PerfEvidenceCursorRequest,
        work: PerfCursorWork,
    ): PerfCursorManifest = files.lock().use {
        val now = clock()
        val available = PERF_CURSOR_DISK_BYTES - files.prune(now) - EVIDENCE_DEDUP_BYTES - METADATA_RESERVE
        if (available <= 0) throw PerfReadRefusal(PerfReadIssue.CURSOR_DISK_LIMIT)
        val settled = AsyncFileIo.awaitDirectory(directory)
        val id = UUID.randomUUID().toString()
        try {
            build(request, work, id, available, settled)
        } catch (failure: IOException) {
            files.discard(id)
            throw failure
        }
    }

    private fun build(
        request: PerfEvidenceCursorRequest,
        work: PerfCursorWork,
        id: String,
        available: Long,
        settled: Boolean,
    ): PerfCursorManifest {
        val owner: PerfEvidenceSeen = files.evidenceIds(id)
        return owner.use { seen ->
            val scan = PerfEvidenceCursorScan(request, inventory, work, seen, available)
            files.create(id).use(scan::scan)
            scan.issues += inventory.issues().map { it.code }
            if (!settled) scan.issues += "evidence_pending_write"
            val manifest = PerfCursorManifest(
                id, clock(), request.query.fromMs, request.query.untilMs,
                PerfEvidencePageMetadata.key(
                    request,
                ),
                scan.segments, scan.matching, scan.issues, scan.malformed, scan.scanned, work.bytes,
            )
            files.save(manifest)
            manifest
        }
    }

    private fun validate(manifest: PerfCursorManifest, request: PerfEvidenceCursorRequest) {
        if (clock() - manifest.asOfMs !in 0..PERF_CURSOR_TTL_MS) {
            throw PerfReadRefusal(
                PerfReadIssue.CURSOR_SNAPSHOT_EXPIRED,
            )
        }
        val times = manifest.fromMs == request.query.fromMs && manifest.untilMs == request.query.untilMs
        require(times && manifest.filterHash == PerfEvidencePageMetadata.key(request)) { "cursor_filter_changed" }
    }

    private fun valid(request: PerfEvidenceCursorRequest): Boolean {
        val interval = request.query.fromMs >= 0 && request.query.untilMs > request.query.fromMs
        val requestedHead = request.query.head
        val head = requestedHead == null || evidenceHeadPattern.matches(requestedHead)
        val requestedCursor = request.cursor
        val cursor = requestedCursor == null || EVIDENCE_CURSOR.matches(requestedCursor)
        return listOf(
            interval,
            head,
            cursor,
            validFilter(request),
            request.pageSize in 1..MAX_EVIDENCE_PAGE_SIZE,
        ).all { it }
    }

    private fun validFilter(request: PerfEvidenceCursorRequest): Boolean =
        EVIDENCE_FILTER.matches(request.filterHash) && (request.predicate == null || request.filterHash.isNotEmpty())

    private fun failure(request: PerfEvidenceCursorRequest, status: String, reason: String): PerfEvidencePage =
        PerfEvidencePage(
            emptyList(),
            emptyList(),
            null,
            PerfEvidencePageMetadata.failure(request.query, status, reason),
        )
}

// Leave 256 KiB of cursor disk capacity for manifest metadata after reserving deduplication and index space.
private const val METADATA_RESERVE = 256L * 1_024

// Cap event pages at one thousand observations before response allocation.
private const val MAX_EVIDENCE_PAGE_SIZE = 1_000
private val EVIDENCE_CURSOR = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9]{1,10}")

private val EVIDENCE_FILTER = Regex("[A-Za-z0-9_-]{0,128}")
