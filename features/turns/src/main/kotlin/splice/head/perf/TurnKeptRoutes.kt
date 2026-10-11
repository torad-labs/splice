// NEW: V4-381 — the guarded console's source-derived inventory of retained turn statistics.
package splice.head.perf

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.config.StatePaths
import splice.core.perf.PerfFiles
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.http.JsonReply
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

private const val TOTALS_SUFFIX = "-session-totals.json"

// why: scanning and deleting years of perf rows may take longer than one head's drain budget;
// the file lane still owns the task if this bounded HTTP wait expires.
private const val TURN_DELETE_WAIT_MS = 120_000L

/** A new successful perf append clears the durable post-delete state. */
internal const val TURN_STATS_DELETED_MARKER = "turns.deleted"

/** This failure's text is fixed by us, so it cannot quote a file name or its bytes. */
private class InvalidTurnFile : IOException("not a regular turn file")

// why: a perf file is read in megabyte chunks, and the first and last 4 KiB of what was counted tell an appended file from a rewritten one.
private const val CHUNK_BYTES = 1 shl 20
private const val CHECK_BYTES = 4096
private const val NEWLINE: Byte = 10

private data class KeptTurnFiles(val perf: List<Path>, val totals: List<Path>)
private data class DeletedTurns(val files: Int, val rows: Long, val body: String)

internal class TurnTally {
    val days = HashSet<LocalDate>()
    var rows = 0L
    var unknown = 0L

    fun add(day: LocalDate?) {
        rows += 1
        if (day == null) unknown += 1 else days += day
    }

    fun copy(): TurnTally = TurnTally().also { it.merge(this) }

    fun merge(other: TurnTally) {
        rows += other.rows
        unknown += other.unknown
        days += other.days
    }
}

/** The day a perf row says it was written, or null when it names none. */
internal fun interface TurnRowDay {
    fun of(line: String): LocalDate?
}

/** What one perf file held when it was last read: where its last whole row ended and a checksum of the bytes around
 *  there, so a later read can tell an appended file from a replaced one. */
private data class SeenTurns(val fileKey: String?, val consumed: Long, val checksum: Long, val tally: TurnTally)

/**
 * The turn rows each perf file held when last counted. A count of years of turns re-parsed every row of every file on
 * each call (about 850 MB, 3 to 7 s on a live daemon, which kept Settings blank); a file already seen is read only
 * from where its last whole row ended. A file that was replaced, shrunk, or rewritten under that point (another file
 * key, a smaller size, a different checksum there) is read again from its start, so a rotation can never be missed.
 */
public class TurnTallies {
    private val seen = ConcurrentHashMap<Path, SeenTurns>()

    /** Forget everything: after a deletion no file is the one that was counted. */
    internal fun clear() = seen.clear()

    internal fun of(file: Path, rowDay: TurnRowDay): TurnTally {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw InvalidTurnFile()
        val fileKey = Files.getAttribute(file, "fileKey", LinkOption.NOFOLLOW_LINKS)?.toString()
        return FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val earlier = seen[file]?.takeIf { continues(it, channel, fileKey) }
            val tally = earlier?.tally?.copy() ?: TurnTally()
            val read = read(channel, earlier?.consumed ?: 0L, tally, rowDay)
            seen[file] = SeenTurns(fileKey, read.end, checksum(channel, read.end), tally.copy())
            // the partial row a writer has not finished counts now and is read whole next time
            if (read.partial.isNotEmpty()) tally.add(rowDay.of(String(read.partial, Charsets.UTF_8)))
            tally
        }
    }

    /** Whether the file is still the one [seen] counted, grown since: the same file, no shorter, its bytes there unchanged. */
    private fun continues(seen: SeenTurns, channel: FileChannel, fileKey: String?): Boolean =
        seen.fileKey == fileKey && channel.size() >= seen.consumed &&
            checksum(channel, seen.consumed) == seen.checksum

    private data class Read(val end: Long, val partial: ByteArray)

    /** Counts every newline-ended row from [start], returning where the last one ended and the unfinished tail. */
    private fun read(channel: FileChannel, start: Long, tally: TurnTally, rowDay: TurnRowDay): Read {
        val buffer = ByteBuffer.allocate(CHUNK_BYTES)
        val bytes = buffer.array()
        val row = ByteArrayOutputStream()
        var chunkStart = start
        var end = start
        channel.position(start)
        while (true) {
            val count = channel.read(buffer)
            if (count <= 0) break
            var from = 0
            for (at in 0 until count) {
                if (bytes[at] == NEWLINE) {
                    row.write(bytes, from, at - from)
                    tally.add(rowDay.of(String(row.toByteArray(), Charsets.UTF_8)))
                    row.reset()
                    from = at + 1
                    end = chunkStart + from
                }
            }
            row.write(bytes, from, count - from)
            chunkStart += count
            buffer.clear()
        }
        return Read(end, row.toByteArray())
    }

    /** A checksum of the first and the last bytes before [end]: an append leaves both as they were. */
    private fun checksum(channel: FileChannel, end: Long): Long {
        val crc = CRC32()
        for (from in longArrayOf(0L, (end - CHECK_BYTES).coerceAtLeast(0L))) {
            val buffer = ByteBuffer.allocate(CHECK_BYTES)
            channel.read(buffer, from)
            buffer.flip()
            val usable = minOf(buffer.remaining().toLong(), (end - from).coerceAtLeast(0L)).toInt()
            crc.update(buffer.array(), 0, usable)
        }
        return crc.value
    }
}

/** A physical count, not a list of configured heads: removed heads can still have private turns. */
public class TurnKeptRoutes(
    private val paths: StatePaths?,
    private val liveTotals: Map<String, SessionTotals> = emptyMap(),
    private val log: LogSink = LogSink(DaemonLog::write),
    private val tallies: TurnTallies = TurnTallies(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Inventory live, rolled and archived perf rows; session totals count only toward deletion state. */
    public fun kept(): JsonReply {
        val source = paths ?: return refuse(HttpStatusCode.ServiceUnavailable, "turn statistics paths are not wired")
        return Cancellables.runCatchingCancellable {
            if (!AsyncFileIo.awaitDirectory(source.stateDir)) {
                throw IOException("pending turn-statistics writes did not settle")
            }
            inventory(source)
        }.fold(
            onSuccess = { JsonReply(HttpStatusCode.OK, it) },
            onFailure = { failure ->
                val detail = when (failure) {
                    is InvalidTurnFile -> "not a regular turn file"
                    else -> SafeFailureText.render(failure)
                }
                refuse(
                    HttpStatusCode.InternalServerError,
                    "cannot inspect turn statistics: $detail",
                )
            },
        )
    }

    /** Remove all retained turn lines and derived session totals after earlier file-lane appends.
     *  A later successful turn starts a new live file and clears the durable deletion marker. */
    public fun delete(): JsonReply {
        val source = paths ?: return refuse(HttpStatusCode.ServiceUnavailable, "turn statistics paths are not wired")
        return Cancellables.runCatchingCancellable { deleteAfterWrites(source) }.fold(
            onSuccess = { removed ->
                log("[turn-stats] deleted ${removed.files} file(s), ${removed.rows} row(s)\n")
                JsonReply(HttpStatusCode.OK, removed.body)
            },
            onFailure = { failure ->
                val detail = when (failure) {
                    is InvalidTurnFile -> "not a regular turn file"
                    else -> SafeFailureText.render(failure)
                }
                refuse(HttpStatusCode.InternalServerError, "cannot delete turn statistics: $detail")
            },
        )
    }

    private fun deleteAfterWrites(source: StatePaths): DeletedTurns {
        val ready = CountDownLatch(1)
        val result = AtomicReference<Result<DeletedTurns>?>(null)
        val queued = AsyncFileIo.submit {
            try {
                result.set(Cancellables.runCatchingCancellable { remove(source) })
            } finally {
                ready.countDown()
            }
        }
        if (!queued) throw IOException("the file lane refused the turn-statistics deletion")
        if (!ready.await(TURN_DELETE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw IOException("turn-statistics deletion is still running; read inventory before retrying")
        }
        return checkNotNull(result.get()).getOrThrow()
    }

    private fun inventory(source: StatePaths): String {
        val kept = files(source)
        val tally = tally(kept.perf)
        val deleted = kept.perf.isEmpty() && kept.totals.isEmpty() &&
            Files.isRegularFile(source.stateDir.resolve(TURN_STATS_DELETED_MARKER), LinkOption.NOFOLLOW_LINKS)
        return JsonWire.string(
            buildJsonObject {
                put("store", "turns")
                put("state", if (deleted) "deleted" else "on")
                if (deleted) {
                    put("reason", "turns deleted")
                } else if (tally.unknown > 0) {
                    put("reason", "${tally.unknown} turn rows have no usable timestamp")
                }
                put("days", tally.days.size)
                put("rows", tally.rows)
                // What it costs on disk, which a count of rows cannot say: a turn row's length varies by an order of
                // magnitude. Settings > Your data reports a store's size beside what it holds, so the person deciding
                // whether to keep it can see what keeping it costs. The derived totals count too: they are kept here
                // and deleted here. A symlink is measured as the link, never followed, which is the same rule the
                // tally reads by.
                put("bytes", bytes(kept.perf + kept.totals))
                put("oldest", tally.days.minOrNull()?.toString())
                // Neither a live generation nor an archive is swept on a clock (PerfStats.sweepArchive).
                put("ages_out", null as String?)
            },
        )
    }

    private fun remove(source: StatePaths): DeletedTurns {
        val kept = files(source)
        val before = tally(kept.perf)
        validateTotals(kept.totals)
        val removed = kept.perf.count { Files.deleteIfExists(it) } + deleteTotals(kept.totals)
        tallies.clear()
        val _ = SecureFile.ownerOnlyDirectory(source.stateDir)
        SecureFile.writeAtomic0600(source.stateDir.resolve(TURN_STATS_DELETED_MARKER), "deleted\n")
        return DeletedTurns(removed, before.rows, inventory(source))
    }

    private fun validateTotals(files: List<Path>) {
        files.forEach { file ->
            if (!Files.isSymbolicLink(file) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw InvalidTurnFile()
            }
        }
    }

    private fun deleteTotals(files: List<Path>): Int {
        val live = liveTotals.values.count { it.deleteKept() }
        val orphaned = files.filterNot { it.fileName.toString().removeSuffix(TOTALS_SUFFIX) in liveTotals }
        return live + orphaned.count { Files.deleteIfExists(it) }
    }

    private fun files(source: StatePaths): KeptTurnFiles {
        val live = entries(source.stateDir)
        val archive = entries(source.perfArchiveDir)
        return KeptTurnFiles(
            perf = live.filter { PerfFiles.isLive(it.fileName.toString()) } +
                archive.filter { PerfFiles.isArchived(it.fileName.toString()) },
            totals = live.filter { it.fileName.toString().endsWith(TOTALS_SUFFIX) },
        )
    }

    /** The bytes [files] occupy. A file that vanished between the listing and here contributes nothing rather than
     *  failing the read: an inventory is a reading of a moment, and a rotation is allowed to move under it. */
    private fun bytes(files: List<Path>): Long = files.sumOf { file ->
        if (Files.isSymbolicLink(file)) {
            0L
        } else {
            try {
                Files.size(file)
            } catch (_: IOException) {
                0L
            }
        }
    }

    private fun tally(files: List<Path>): TurnTally = TurnTally().also { count ->
        val day = TurnRowDay { rowDay(it) }
        files.forEach { file -> if (!Files.isSymbolicLink(file)) count.merge(tallies.of(file, day)) }
    }

    private fun entries(dir: Path): List<Path> {
        if (Files.isSymbolicLink(dir)) throw IOException("turn statistics directory is a symlink: $dir")
        return try {
            Files.newDirectoryStream(dir).use { it.toList() }
        } catch (_: NoSuchFileException) {
            emptyList()
        }
    }

    private fun rowDay(line: String): LocalDate? = Cancellables.runCatchingCancellable {
        val stamp = (json.parseToJsonElement(line).jsonObject["ts"] as? JsonPrimitive)?.longOrNull
        stamp?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    }.fold(onSuccess = { it }, onFailure = { null })

    private fun refuse(status: HttpStatusCode, why: String): JsonReply =
        JsonReply(status, JsonWire.string(buildJsonObject { put("error", why) }))
}
