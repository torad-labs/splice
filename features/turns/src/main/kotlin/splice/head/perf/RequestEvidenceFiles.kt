package splice.head.perf

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
import splice.core.util.JsonWire
import splice.core.util.JsonlSink
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** One daemon-owned directory and disk cap shared by every head. All filesystem work runs on its file lane. */
public class RequestEvidenceFiles(
    directory: Path,
    private val kept: KeptHistory = KeptHistory { HistoryWindow(HISTORY_DEFAULT_DAYS) },
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val maxBytes: Long = EVIDENCE_DISK_BYTES,
) {
    private val directory = directory.toAbsolutePath().normalize()
    internal val lane: Path = this.directory.resolve("request-evidence-lane")
    private val current = LinkedHashMap<String, Path>()
    private var retention: RequestEvidenceRetention? = null

    init {
        require(maxBytes >= MIN_EVIDENCE_BYTES)
    }

    public fun forHead(head: String): RequestEvidenceStore {
        require(head.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")))
        return RequestEvidenceStore(this, head, clock)
    }

    @Synchronized
    internal fun append(head: String, line: String) {
        SecureFile.ownerOnlyDirectory(directory)?.let { throw IOException("request evidence directory is not private") }
        restoreRetention()
        val size = JsonWire.byteSize(line) + 1L
        if (size > maxBytes - EVIDENCE_MANIFEST_BYTES) throw IOException("request evidence exceeds disk budget")
        val files = segments()
        current.entries.removeIf { it.value !in files }
        expire(files, size)
        val file = target(head, size)
        JsonlSink.appendLine(file, line, maxBytes = Long.MAX_VALUE, force = JsonlSink.PAGE_CACHE_FORCE)
        saveRetention()
    }

    private fun target(head: String, bytes: Long): Path {
        val limit = minOf(EVIDENCE_SEGMENT_BYTES, maxBytes - EVIDENCE_MANIFEST_BYTES)
        val previous = current[head]?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        if (previous != null && Files.size(previous) + bytes <= limit) return previous
        return directory.resolve("evidence-$head-${clock().coerceAtLeast(0)}-${UUID.randomUUID()}.jsonl")
            .also { current[head] = it }
    }

    private fun segments(): List<Path> = Files.newDirectoryStream(directory).use { entries ->
        val found = entries.asSequence().filter { RequestEvidenceFormat.isSegment(it.fileName.toString()) }
            .take(MAX_EVIDENCE_SEGMENTS + 1)
            .onEach {
                if (!Files.isRegularFile(
                        it,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                ) {
                    throw IOException("invalid evidence segment")
                }
            }
            .toList()
        if (found.size > MAX_EVIDENCE_SEGMENTS) throw IOException("request evidence segment bound exceeded")
        found.sortedBy { Files.getLastModifiedTime(it).toMillis() }
    }

    private fun expire(files: List<Path>, incoming: Long) {
        var bytes = files.sumOf { Files.size(it) }
        var count = files.size
        val cutoff = kept.now().cutoffMs(clock())
        for (file in files) {
            val modified = Files.getLastModifiedTime(file).toMillis()
            val old = cutoff != null && modified < cutoff
            val fits = bytes + incoming <= maxBytes - EVIDENCE_MANIFEST_BYTES && count < MAX_EVIDENCE_SEGMENTS
            if (!old && fits) continue
            val previous = checkNotNull(retention)
            retention = previous.copy(
                expiredSegments = previous.expiredSegments + 1,
                expiredBeforeWriteMs = maxOf(previous.expiredBeforeWriteMs ?: 0L, modified),
            )
            // Publish expiry before deleting data, so even a crash cannot make missing evidence look complete.
            saveRetention()
            bytes -= Files.size(file)
            Files.delete(file)
            Files.deleteIfExists(file.resolveSibling("${file.fileName}.lock"))
            count--
            current.entries.removeIf { it.value == file }
        }
    }

    private fun restoreRetention() {
        if (retention != null) return
        val path = directory.resolve(RequestEvidenceFormat.RETENTION_FILE)
        retention = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > EVIDENCE_MANIFEST_BYTES) {
                throw IOException("invalid request evidence retention metadata")
            }
            Json.decodeFromString<RequestEvidenceRetention>(Files.readString(path))
        } else {
            RequestEvidenceRetention(failedMaintenance = if (segments().isEmpty()) 0L else 1L)
        }
    }

    private fun saveRetention() {
        SecureFile.writeAtomic0600(
            directory.resolve(RequestEvidenceFormat.RETENTION_FILE),
            Json.encodeToString(checkNotNull(retention)),
        )
    }
}

private const val EVIDENCE_DISK_BYTES = 64L * 1_024 * 1_024
private const val EVIDENCE_SEGMENT_BYTES = 1_024L * 1_024
private const val EVIDENCE_MANIFEST_BYTES = 4_096L
private const val MIN_EVIDENCE_BYTES = 8_192L
private const val MAX_EVIDENCE_SEGMENTS = 128
