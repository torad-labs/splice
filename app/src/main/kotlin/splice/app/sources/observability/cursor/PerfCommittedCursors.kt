package splice.app.sources.observability.cursor

import splice.app.sources.observability.PerfSourceCosts
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.history.PerfCursorPage
import splice.usage.perf.history.PerfCursorRequest
import splice.usage.perf.history.PerfCursorSource
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Semaphore

/** Persistent metadata/index only. Raw records remain in their original retention-controlled segments. */
internal class PerfCommittedCursors(
    private val live: Path,
    archive: Path?,
    private val costs: PerfSourceCosts,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : PerfCursorSource {
    private val inventory = PerfCursorInventory(live, archive)
    private val files = PerfCursorFiles(live.resolveSibling("${live.fileName}-cursors"))

    override fun page(request: PerfCursorRequest): PerfCursorPage = when {
        !valid(request) -> failure(request, "invalid", "invalid_cursor_query")
        !PerfCursorLane.queries.tryAcquire() -> failure(request, "busy", "query_busy")
        else -> admitted(request)
    }

    private fun admitted(request: PerfCursorRequest): PerfCursorPage {
        val lease = PerfWorkBudget.acquire(PERF_CURSOR_RESERVE_BYTES)
        if (lease == null) {
            PerfCursorLane.queries.release()
            return failure(request, "busy", "memory_busy")
        }
        return execute(request, lease)
    }

    private fun execute(request: PerfCursorRequest, lease: PerfWorkBudget.Lease): PerfCursorPage {
        val started = MonoClock.nowMs()
        val work = PerfCursorWork()
        costs.begin(request.cursor == null)
        var scannedRows = 0L
        var retained = false
        return try {
            val manifest = load(request, work)
            val offset = request.cursor?.substringAfter(':')?.toLongOrNull() ?: 0L
            if (offset !in 0..manifest.matching) throw PerfReadRefusal(PerfReadIssue.CURSOR_OFFSET_INVALID)
            val reader = PerfCursorPageRead(files, inventory)
            val page = reader.read(manifest, offset, request, work)
            scannedRows = if (request.cursor == null) manifest.scannedRows else page.rows.size.toLong()
            lease.retain(page, reader.retainedBytes)
            retained = true
            page
        } catch (_: NoSuchFileException) {
            failure(request, "expired", "cursor_snapshot_expired")
        } catch (failure: PerfReadRefusal) {
            failed(request, failure.issue)
        } catch (_: IOException) {
            failure(request, "expired", "cursor_read_error")
        } catch (_: IllegalArgumentException) {
            failure(request, "invalid", "cursor_malformed")
        } finally {
            if (!retained) lease.close()
            costs.end(scannedRows, work.bytes, (MonoClock.nowMs() - started).coerceAtLeast(0L))
            PerfCursorLane.queries.release()
        }
    }

    private fun load(request: PerfCursorRequest, work: PerfCursorWork): PerfCursorManifest {
        val manifest = request.cursor?.let { files.load(it.substringBefore(':')) } ?: create(request, work)
        validate(manifest, request)
        return manifest
    }

    private fun failed(request: PerfCursorRequest, issue: PerfReadIssue): PerfCursorPage {
        val reason = issue.code
        val status = when {
            reason == "cursor_filter_changed" || reason == "cursor_offset_invalid" -> "invalid"
            reason == "cursor_busy" -> "busy"
            reason.endsWith("_limit") -> "incomplete"
            else -> "expired"
        }
        return failure(request, status, reason)
    }

    private fun create(request: PerfCursorRequest, work: PerfCursorWork): PerfCursorManifest = files.lock().use {
        val now = clock()
        val held = files.prune(now, reserveFiles = 2)
        if (held >= PERF_CURSOR_DISK_BYTES - CURSOR_METADATA_ALLOWANCE) {
            throw PerfReadRefusal(
                PerfReadIssue.CURSOR_DISK_LIMIT,
            )
        }
        val settled = AsyncFileIo.awaitFile(live)
        val id = UUID.randomUUID().toString()
        val scan = PerfCursorScan(inventory, request, work, PERF_CURSOR_DISK_BYTES - held - CURSOR_METADATA_ALLOWANCE)
        var committed = false
        Cancellables.withCleanup({ if (!committed) files.discard(id) }) {
            files.create(id).use(scan::scan)
            if (!settled) scan.issues += "pending_write_uncertain"
            val manifest = PerfCursorManifest(
                id, now, request.query.fromMs, request.query.untilMs, request.query.filterHash,
                scan.segments, scan.matching, scan.issues, scan.malformed, scan.rows, work.bytes,
            )
            files.save(manifest)
            committed = true
            manifest
        }
    }

    private fun validate(manifest: PerfCursorManifest, request: PerfCursorRequest) {
        val age = clock() - manifest.asOfMs
        if (age !in 0..PERF_CURSOR_TTL_MS || manifest.schemaVersion != 1) {
            throw PerfReadRefusal(
                PerfReadIssue.CURSOR_SNAPSHOT_EXPIRED,
            )
        }
        val changedInterval = manifest.fromMs != request.query.fromMs || manifest.untilMs != request.query.untilMs
        if (manifest.filterHash != request.query.filterHash || changedInterval) {
            throw PerfReadRefusal(PerfReadIssue.CURSOR_FILTER_CHANGED)
        }
    }

    private fun valid(request: PerfCursorRequest): Boolean = request.query.fromMs >= 0 &&
        request.query.untilMs > request.query.fromMs && request.query.filterHash.length in 1..MAX_FILTER_HASH &&
        request.pageSize in 1..MAX_COMMITTED_PAGE_SIZE && request.cursor.let { cursor ->
            cursor == null || TOKEN.matches(cursor)
        }

    private fun failure(request: PerfCursorRequest, status: String, reason: String): PerfCursorPage {
        if (status == "busy") costs.refused()
        return PerfCursorPage(emptyList(), emptyList(), null, PerfCursorMetadata.failure(request.query, status, reason))
    }
}

private object PerfCursorLane {
    val queries = Semaphore(2)
}

private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9]{1,10}")

// Leave 256 KiB of cursor disk capacity for its manifest when reserving the fixed-width row index.
private const val CURSOR_METADATA_ALLOWANCE = 256L * 1_024

// Bound selection fingerprints to 256 characters before persisting cursor identity.
private const val MAX_FILTER_HASH = 256

// Cap each committed-row page at one thousand records before allocating its response.
private const val MAX_COMMITTED_PAGE_SIZE = 1_000
