package splice.app.sources.observability

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
        val lease = PerfWorkBudget.acquire(PERF_CURSOR_RESERVE_BYTES)
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
        } catch (error: IOException) {
            failed(request, error)
        } catch (_: IllegalArgumentException) {
            failure(request, "invalid", "cursor_invalid_or_changed")
        } finally {
            if (!retained) lease.close()
            costs.end(scanned, work.bytes, (MonoClock.nowMs() - started).coerceAtLeast(0L))
            queries.release()
        }
    }

    private fun failed(request: PerfEvidenceCursorRequest, error: IOException): PerfEvidencePage {
        val reason = error.message?.takeIf { it.startsWith("cursor_") } ?: "cursor_read_error"
        val status = if (reason.endsWith("_limit")) "incomplete" else "expired"
        return failure(request, status, reason)
    }

    private fun create(
        request: PerfEvidenceCursorRequest,
        work: PerfCursorWork,
    ): PerfCursorManifest = files.lock().use {
        val now = clock()
        val available = PERF_CURSOR_DISK_BYTES - files.prune(now) - EVIDENCE_DEDUP_BYTES - METADATA_RESERVE
        if (available <= 0) throw IOException("cursor_disk_limit")
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
    ): PerfCursorManifest = files.evidenceIds(id).use { seen ->
        val scan = PerfEvidenceCursorScan(request.query, inventory, work, seen, available)
        files.create(id).use(scan::scan)
        scan.issues += inventory.issues().map { it.code }
        if (!settled) scan.issues += "evidence_pending_write"
        val manifest = PerfCursorManifest(
            id, clock(), request.query.fromMs, request.query.untilMs,
            PerfEvidencePageMetadata.key(
                request.query,
            ),
            scan.segments, scan.matching, scan.issues, scan.malformed, scan.scanned, work.bytes,
        )
        files.save(manifest)
        manifest
    }

    private fun validate(manifest: PerfCursorManifest, request: PerfEvidenceCursorRequest) {
        if (clock() - manifest.asOfMs !in 0..PERF_CURSOR_TTL_MS) throw IOException("cursor_snapshot_expired")
        val times = manifest.fromMs == request.query.fromMs && manifest.untilMs == request.query.untilMs
        require(times && manifest.filterHash == PerfEvidencePageMetadata.key(request.query)) { "cursor_filter_changed" }
    }

    private fun valid(request: PerfEvidenceCursorRequest): Boolean {
        val interval = request.query.fromMs >= 0 && request.query.untilMs > request.query.fromMs
        val head = request.query.head == null || evidenceHeadPattern.matches(request.query.head)
        val cursor = request.cursor == null || EVIDENCE_CURSOR.matches(request.cursor)
        return interval && head && cursor && request.pageSize in 1..MAX_EVIDENCE_PAGE_SIZE
    }

    private fun failure(request: PerfEvidenceCursorRequest, status: String, reason: String): PerfEvidencePage =
        PerfEvidencePage(
            emptyList(),
            emptyList(),
            null,
            PerfEvidencePageMetadata.failure(request.query, status, reason),
        )
}

private const val METADATA_RESERVE = 256L * 1_024
private const val MAX_EVIDENCE_PAGE_SIZE = 1_000
private val EVIDENCE_CURSOR = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9]{1,10}")
