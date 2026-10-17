// NEW: rolling trace-body capacity and body-only eviction shared across every head.
package splice.core.storage

import splice.core.util.Cancellables
import splice.core.util.JsonlForce
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Rolling compressed body capacity shared by every head in a trace directory. */
public const val TRACE_BODY_MAX_BYTES: Long = 32L shl 30

/** Rolling JSONL index capacity shared by every head in a trace directory: past it the oldest completed day goes whole
 *  (splice-lead, Oct 12). The history window still bounds both; this keeps a busy window inside the disk. */
internal const val TRACE_INDEX_MAX_BYTES: Long = 16L shl 30

/** Trace bodies never consume the volume's last 64 GiB of usable space. */
internal const val TRACE_BODY_MIN_FREE_BYTES: Long = 64L shl 30

/** An eviction tombstone preserves the omission's reason without deleting the day's trace records. */
internal const val DAY_BODY_EVICTED_SUFFIX: String = ".bodies-evicted"

public const val BODY_BUDGET_EVICTED_REASON: String = "evicted by the body budget"

/** Why a write was refused below the free-space floor: a body, or the index line beside it. */
private const val FLOOR_REACHED = "trace volume free-space floor reached"

private const val INDEX_CAP_REACHED = "shared trace index budget exhausted by the day being written"

/** The usable bytes on the volume containing a trace directory. */
public fun interface DayVolumeSpace {
    public fun available(dir: Path): Long
}

/** A refused body remains explicit metadata, not a failed or partially readable trace record. */
public class DayBodyCapacityException(public val reason: String) : IOException(reason)

/** Admission and body-only eviction are serialized with every day index writer and purge. */
public class DayBodyBudget(
    public val maxBytes: Long = TRACE_BODY_MAX_BYTES,
    private val minFreeBytes: Long = TRACE_BODY_MIN_FREE_BYTES,
    private val space: DayVolumeSpace = DayVolumeSpace { Files.getFileStore(it).usableSpace },
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val force: JsonlForce = JsonlForce { _, channel -> channel.force(true) },
    private val indexMaxBytes: Long = TRACE_INDEX_MAX_BYTES,
) {
    init {
        require(maxBytes > 0)
        require(minFreeBytes >= 0)
    }

    public fun <T> withLock(dir: Path, action: DayDirectoryAction<T>): T =
        DayDirectoryLock(dir).withLock(action)

    /** Existing chunks need no admission. Only new compressed bytes, including their framing, reach here. */
    public fun admit(pack: Path, bytes: Long): Unit = withLock(pack.parent) {
        require(bytes >= 0)
        if (evicted(dayFile(pack))) throw DayBodyCapacityException(BODY_BUDGET_EVICTED_REASON)
        if (bytes > maxBytes) throw DayBodyCapacityException("shared rolling trace body budget exhausted")
        var days = inventory(pack.parent)
        val today = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()
        val active = dateOf(dayFile(pack))
        while (!fits(pack.parent, days.sumOf { it.bytes }, bytes)) {
            val oldest = days.filter { it.date.isBefore(today) && it.date != active }.minByOrNull { it.date }
                ?: throw DayBodyCapacityException(reason(pack.parent, bytes))
            // A whole completed UTC day goes together, across every head and both body formats.
            days.filter { it.date == oldest.date }.forEach(::evict)
            days = inventory(pack.parent)
        }
    }

    public fun evicted(day: Path): Boolean =
        Files.isRegularFile(marker(day), NOFOLLOW_LINKS)

    /** The index line [bytes] long that [day] takes. Below the bodies' free-space floor it is refused. Past the shared
     *  index cap the oldest completed UTC day goes whole, across every head (its index, every rolled generation and
     *  its bodies), never today nor the day being written. Null when the line may be written, else why not. Call
     *  under [withLock]. */
    public fun admitIndex(day: Path, bytes: Long): String? {
        val available = space.available(day.parent)
        if (available < minFreeBytes || bytes > available - minFreeBytes) return FLOOR_REACHED
        val today = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()
        val active = dateOf(day)
        var days = indexDays(day.parent)
        while (days.sumOf { it.second } > indexMaxBytes - bytes) {
            val oldest = days.map { it.first }.filter { it.isBefore(today) && it != active }.minOrNull()
                ?: return INDEX_CAP_REACHED
            removeDay(day.parent, oldest)
            days = indexDays(day.parent)
        }
        return null
    }

    /** Each JSONL index file's day and size, every rolled generation included; body packs and markers are not
     *  the index. */
    private fun indexDays(dir: Path): List<Pair<LocalDate, Long>> =
        DirectoryEntries.of(dir).mapNotNull { file ->
            val date = dateOf(file) ?: return@mapNotNull null
            val attributes = Files.readAttributes(file, "basic:size,isRegularFile", NOFOLLOW_LINKS)
            if (attributes["isRegularFile"] == true) date to attributes["size"] as Long else null
        }

    /** Deletes every head's files of one day: the index, every rolled generation, its lock, its bodies and
     *  their markers. */
    private fun removeDay(dir: Path, date: LocalDate) {
        DirectoryEntries.of(dir).filter { file ->
            val day = DayGenerations.base(file.fileName.toString())
            daySiblings.any { suffix -> day.endsWith("-$date.jsonl$suffix") }
        }.forEach { Files.deleteIfExists(it) }
    }

    private fun fits(dir: Path, stored: Long, growth: Long): Boolean =
        growth <= maxBytes && stored <= maxBytes - growth &&
            space.available(dir).let { it >= minFreeBytes && growth <= it - minFreeBytes }

    private fun reason(dir: Path, growth: Long): String =
        if (space.available(dir).let { it < minFreeBytes || growth > it - minFreeBytes }) {
            FLOOR_REACHED
        } else {
            "shared rolling trace body budget exhausted"
        }

    private fun inventory(dir: Path): List<BodyDay> =
        DirectoryEntries.of(dir).mapNotNull { file ->
            val suffix = listOf(DAY_BODY_V2_SUFFIX, DAY_BODY_SUFFIX).firstOrNull {
                file.fileName.toString().endsWith(".jsonl$it")
            } ?: return@mapNotNull null
            val day = file.resolveSibling(file.fileName.toString().removeSuffix(suffix))
            val date = dateOf(day) ?: return@mapNotNull null
            val attributes = Files.readAttributes(file, "basic:size,isRegularFile", NOFOLLOW_LINKS)
            if (attributes["isRegularFile"] != true) return@mapNotNull null
            BodyDay(day, date, file, attributes["size"] as Long)
        }

    private fun evict(body: BodyDay) {
        // Both forces precede unlink: a crash cannot erase the omission's reason while keeping its deletion.
        writeMarker(marker(body.day))
        Files.deleteIfExists(body.pack)
    }

    private fun writeMarker(path: Path) {
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        try {
            SecureFile.writeAtomic0600(temporary, BODY_BUDGET_EVICTED_REASON + "\n")
            FileChannel.open(temporary, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { force.force(temporary, it) }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            FileChannel.open(path.parent, StandardOpenOption.READ).use { force.force(path.parent, it) }
        } finally {
            Cancellables.discard(
                Cancellables.runCatchingCleanup { Files.deleteIfExists(temporary) },
                "eviction marker temp cleanup is best-effort; a failed force keeps the body pack",
            )
        }
    }

    private fun marker(day: Path): Path =
        day.resolveSibling(DayGenerations.base(day.fileName.toString()) + DAY_BODY_EVICTED_SUFFIX)

    private fun dayFile(pack: Path): Path = pack.resolveSibling(
        pack.fileName.toString().removeSuffix(DAY_BODY_V2_SUFFIX).removeSuffix(DAY_BODY_SUFFIX),
    )

    private fun dateOf(day: Path): LocalDate? {
        val name = DayGenerations.base(day.fileName.toString())
        val date = Regex(".+-(\\d{4}-\\d{2}-\\d{2})\\.jsonl").matchEntire(name)?.groupValues?.get(1) ?: return null
        return try {
            LocalDate.parse(date)
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }

    private data class BodyDay(val day: Path, val date: LocalDate, val pack: Path, val bytes: Long)
}
