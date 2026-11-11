package splice.app.sources.observability.cursor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.sources.CACHE_OVERHEAD_BYTES
import splice.app.sources.ParseTally
import splice.app.sources.PerfLineDecoder
import splice.app.sources.PerfLineReader
import splice.app.sources.PerfRowsCache
import splice.usage.perf.PerfRow
import splice.usage.perf.history.PerfCursorPage
import splice.usage.perf.history.PerfCursorRequest
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class PerfCursorPageRead(private val files: PerfCursorFiles, private val inventory: PerfCursorInventory) {
    var retainedBytes = PAGE_OVERHEAD_BYTES
        private set

    fun read(
        manifest: PerfCursorManifest,
        offset: Long,
        request: PerfCursorRequest,
        work: PerfCursorWork,
    ): PerfCursorPage {
        val paths = CursorSegmentPaths(manifest, inventory, work)
        val selection = CursorPageSelection(manifest, request, paths, work)
        val page = files.index(manifest.id).use { index -> selection.read(index, offset, files) }
        paths.verify()
        retainedBytes = selection.retainedBytes
        return page
    }
}

/**
 * The files a page's rows are read from, each found by its digest when a row first needs it and digested again once
 * the page is read. Until Nov 11, 2026 a page digested every segment of the cursor twice, the ones it took no row
 * from included: a first page of one row read two whole generations four times over.
 */
private class CursorSegmentPaths(
    private val manifest: PerfCursorManifest,
    private val inventory: PerfCursorInventory,
    private val work: PerfCursorWork,
) {
    private val candidates by lazy { inventory.candidates(manifest.fromMs) }
    private val found = LinkedHashMap<Int, Path>()

    fun of(segment: Int): Path = found.getOrPut(segment) {
        inventory.resolve(manifest.segments[segment], candidates, work)
            ?: throw PerfReadRefusal(PerfReadIssue.CURSOR_SEGMENT_EXPIRED)
    }

    fun verify() {
        found.forEach { (index, path) ->
            val segment = manifest.segments[index]
            if (inventory.digest(path, segment.end, work) != segment.digest) {
                throw PerfReadRefusal(PerfReadIssue.CURSOR_SEGMENT_CHANGED)
            }
        }
    }
}

private class CursorPageSelection(
    private val manifest: PerfCursorManifest,
    private val request: PerfCursorRequest,
    private val paths: CursorSegmentPaths,
    private val work: PerfCursorWork,
) {
    private val decoder =
        PerfLineDecoder(PerfRowsCache(CACHE_OVERHEAD_BYTES + PAGE_DECODER_BYTES), Json, ParseTally { })
    private val rows = ArrayList<PerfRow>()
    private val ids = ArrayList<String>()
    var retainedBytes = PAGE_OVERHEAD_BYTES
        private set

    fun read(index: FileChannel, offset: Long, files: PerfCursorFiles): PerfCursorPage {
        if (index.size() != manifest.matching * PERF_CURSOR_ENTRY_BYTES) {
            throw PerfReadRefusal(
                PerfReadIssue.CURSOR_INDEX_CHANGED,
            )
        }
        var at = offset
        while (at < manifest.matching && rows.size < request.pageSize) {
            if (!record(files.position(index, at))) break
            at++
        }
        val next = if (at < manifest.matching) "${manifest.id}:$at" else null
        return PerfCursorPage(rows, ids, next, PerfCursorMetadata.of(manifest))
    }

    private fun record(position: PerfCursorPosition): Boolean {
        val segment = checked(position)
        val charge = (position.end - position.start) * PAGE_DECODE_EXPANSION + PAGE_ROW_OVERHEAD
        if (retainedBytes + charge > PERF_CURSOR_OUTPUT_BYTES) return false
        val raw = raw(paths.of(position.segment), position)
        work.charge(position.end - position.start)
        val row = decoder.decode(raw).row ?: throw PerfReadRefusal(PerfReadIssue.CURSOR_RECORD_CHANGED)
        validate(row)
        retainedBytes += charge
        rows += row
        ids += observationId(raw) ?: "legacy:${segment.id}:${position.start}"
        return true
    }

    private fun checked(position: PerfCursorPosition): PerfCursorSegment {
        val segment = manifest.segments.getOrNull(
            position.segment,
        ) ?: throw PerfReadRefusal(PerfReadIssue.CURSOR_INDEX_INVALID)
        val reversed = position.end <= position.start || position.start < 0
        if (position.end > segment.end || reversed) throw PerfReadRefusal(PerfReadIssue.CURSOR_INDEX_INVALID)
        return segment
    }

    private fun validate(row: PerfRow) {
        val outside = row.ts < manifest.fromMs || row.ts >= manifest.untilMs
        if (outside || !request.predicate.matches(row)) throw PerfReadRefusal(PerfReadIssue.CURSOR_FILTER_CHANGED)
    }

    private fun raw(path: Path, position: PerfCursorPosition): String =
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val reader = PerfLineReader(channel, position.start, position.end, PERF_CURSOR_MAX_LINE_BYTES)
            val raw = reader.next() ?: throw PerfReadRefusal(PerfReadIssue.CURSOR_RECORD_MISSING)
            if (!reader.terminated || reader.position != position.end) {
                throw PerfReadRefusal(
                    PerfReadIssue.CURSOR_RECORD_CHANGED,
                )
            }
            raw
        }

    private fun observationId(raw: String): String? {
        val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val id = obj["observation_id"] as? JsonPrimitive ?: return null
        return id.takeIf { it.isString }?.content?.takeIf {
            it.length in 1..MAX_OBSERVATION_ID && it.none(
                Char::isISOControl,
            )
        }
    }
}

// Keep the reusable legacy-row decoder within a 64 KiB cache charge during page reads.
private const val PAGE_DECODER_BYTES = 64L * 1_024

// Reserve 64 KiB for page lists, cursor metadata and response ownership before adding row charges.
private const val PAGE_OVERHEAD_BYTES = 64L * 1_024

// Charge 2 KiB per decoded row beyond its text expansion for record and collection objects.
private const val PAGE_ROW_OVERHEAD = 2_048L

// Charge eight times the raw row bytes for decoded strings, JSON nodes and row projection.
private const val PAGE_DECODE_EXPANSION = 8L

// Accept observation identifiers up to 256 characters before retaining them in the page metadata.
private const val MAX_OBSERVATION_ID = 256
