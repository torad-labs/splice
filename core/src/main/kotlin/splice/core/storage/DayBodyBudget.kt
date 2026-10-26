// NEW: rolling trace-body capacity and body-only eviction shared across every head.
// 2026-10-26: admission listed and statted the trace directory for each new body chunk, compiled the date pattern
// for each directory entry and found the volume's mount again at each free-space probe, 3.85% of the daemon's
// allocation in the Oct 25 17:59-18:04 CT JFR. Now one lock hold lists the directory once and moves that listing by
// its own grants, listing again after its own deletions. The pattern and the pack endings are built once, and the
// default free-space probe keeps its FileStore.
package splice.core.storage

import splice.core.util.Cancellables
import splice.core.util.JsonlForce
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileStore
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

/** How a day's v2 and v1 body packs end: the day file's `.jsonl`, then the format's suffix. */
private const val DAY_PACK_V2_ENDING = ".jsonl$DAY_BODY_V2_SUFFIX"
private const val DAY_PACK_V1_ENDING = ".jsonl$DAY_BODY_SUFFIX"

/** A day file's name once any rolled generation is cut off, `<head>-<yyyy-MM-dd>.jsonl`, with its date. */
private val DAY_DATE = Regex(".+-(\\d{4}-\\d{2}-\\d{2})\\.jsonl")

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
    private val space: DayVolumeSpace = KeptVolumeSpace(),
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val force: JsonlForce = JsonlForce { _, channel -> channel.force(true) },
    private val indexMaxBytes: Long = TRACE_INDEX_MAX_BYTES,
) {
    // The listing this thread's outermost withLock keeps, absent outside one.
    private val held = ThreadLocal<DayHold>()

    init {
        require(maxBytes > 0)
        require(minFreeBytes >= 0)
    }

    /** Runs [action] under the lock every writer of [dir] takes first, so inside one hold only this budget changes
     *  the packs and index files it counts. The hold's first admission lists [dir], later ones add the hold's own
     *  grants to that listing, and a deletion lists again. Bytes written without a grant inside the same hold, or
     *  admissions continued there after a granted write failed, go unseen until the hold ends. */
    public fun <T> withLock(dir: Path, action: DayDirectoryAction<T>): T = DayDirectoryLock(dir).withLock {
        if (held.get() != null) {
            action()
        } else {
            held.set(DayHold(dir))
            try {
                action()
            } finally {
                held.remove()
            }
        }
    }

    /** Existing chunks need no admission. Only new compressed bytes, including their framing, reach here. */
    public fun admit(pack: Path, bytes: Long): Unit = withLock(pack.parent) {
        require(bytes >= 0)
        val day = dayFile(pack)
        if (evicted(day)) throw DayBodyCapacityException(BODY_BUDGET_EVICTED_REASON)
        if (bytes > maxBytes) throw DayBodyCapacityException("shared rolling trace body budget exhausted")
        val hold = hold(pack.parent)
        val today = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()
        val active = dateOf(day.fileName.toString())
        while (!fits(pack.parent, hold.packs().sumOf { it.bytes }, bytes)) {
            val days = hold.packs()
            val oldest = days.filter { it.date.isBefore(today) && it.date != active }.minByOrNull { it.date }
                ?: throw DayBodyCapacityException(reason(pack.parent, bytes))
            // A whole completed UTC day goes together, across every head and both body formats.
            val gone = days.filter { it.date == oldest.date }
            hold.changed()
            gone.forEach(::evict)
        }
        hold.granted(pack, bytes)
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
        val active = dateOf(day.fileName.toString())
        val hold = hold(day.parent)
        while (hold.index().values.sum() > indexMaxBytes - bytes) {
            val oldest = hold.index().keys.filter { it.isBefore(today) && it != active }.minOrNull()
                ?: return INDEX_CAP_REACHED
            hold.changed()
            removeDay(day.parent, oldest)
        }
        hold.lined(active, bytes)
        return null
    }

    /** The listing this thread's hold keeps for [dir], or one of its own for a call outside any hold or for another
     *  directory. */
    private fun hold(dir: Path): DayHold = held.get()?.takeIf { it.dir == dir } ?: DayHold(dir)

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

    /** The date of the day file or rolled generation named [name], or null when it names no real UTC day. */
    private fun dateOf(name: String): LocalDate? {
        val date = DAY_DATE.matchEntire(DayGenerations.base(name))?.groupValues?.get(1) ?: return null
        return try {
            LocalDate.parse(date)
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }

    private data class BodyDay(val day: Path, val date: LocalDate, val pack: Path, val bytes: Long)

    /** One listing of [dir] for one hold of its lock: each dated body pack with its size, and each date's index bytes
     *  over every head and rolled generation. It is taken at first use, moved by the hold's own grants, and
     *  forgotten before a deletion, so the next use lists again. */
    private inner class DayHold(val dir: Path) {
        private val packs = ArrayList<BodyDay>()
        private val index = HashMap<LocalDate, Long>()
        private var listed = false

        /** Every dated body pack in [dir], of every head and both formats. */
        fun packs(): List<BodyDay> {
            list()
            return packs
        }

        /** Each date's index bytes in [dir]: every head's day file and its rolled generations. */
        fun index(): Map<LocalDate, Long> {
            list()
            return index
        }

        /** [bytes] granted to [pack], which its writer appends before anything in this hold is admitted again. */
        fun granted(pack: Path, bytes: Long) {
            if (!listed) return
            val at = packs.indexOfFirst { it.pack == pack }
            if (at >= 0) {
                packs[at] = packs[at].copy(bytes = packs[at].bytes + bytes)
                return
            }
            val created = bodyDay(pack, pack.fileName.toString(), bytes)
            if (created != null) packs += created
        }

        /** A line of [bytes] granted to a day file dated [date], or to an undated one when it is null. */
        fun lined(date: LocalDate?, bytes: Long) {
            if (listed && date != null) index[date] = index.getOrDefault(date, 0L) + bytes
        }

        /** A file this listing counts is about to be deleted. */
        fun changed() {
            listed = false
        }

        private fun list() {
            if (listed) return
            packs.clear()
            index.clear()
            for (file in DirectoryEntries.of(dir)) count(file, file.fileName.toString())
            listed = true
        }

        /** Counts [file], named [name], when it is a dated body pack or a dated index file, and only when it is a
         *  regular file. */
        private fun count(file: Path, name: String) {
            val body = bodyDay(file, name, 0L)
            val date = body?.date ?: dateOf(name) ?: return
            // The attribute-name overload: a class literal is reflection to kt-no-reflection-in-production.
            val attributes = Files.readAttributes(file, "basic:size,isRegularFile", NOFOLLOW_LINKS)
            if (attributes["isRegularFile"] != true) return
            val size = attributes["size"] as Long
            if (body != null) {
                packs += body.copy(bytes = size)
            } else {
                index[date] = index.getOrDefault(date, 0L) + size
            }
        }

        /** [file], named [name], as a body pack of [bytes] whose day file has a date, or null when it is none. */
        private fun bodyDay(file: Path, name: String, bytes: Long): BodyDay? {
            val suffix = when {
                name.endsWith(DAY_PACK_V2_ENDING) -> DAY_BODY_V2_SUFFIX
                name.endsWith(DAY_PACK_V1_ENDING) -> DAY_BODY_SUFFIX
                else -> return null
            }
            val day = name.removeSuffix(suffix)
            val date = dateOf(day) ?: return null
            return BodyDay(file.resolveSibling(day), date, file, bytes)
        }
    }

    /** The default free-space probe. It keeps the FileStore of the last directory asked about: building one reads the
     *  whole mount table, while its usable space is one statvfs of that directory's own path at each call, so every
     *  answer, and a missing directory's NoSuchFileException, is as fresh as a new store's. */
    private class KeptVolumeSpace : DayVolumeSpace {
        @Volatile
        private var kept: Pair<Path, FileStore>? = null

        override fun available(dir: Path): Long {
            val store = kept?.takeIf { it.first == dir }?.second ?: Files.getFileStore(dir).also { kept = dir to it }
            return store.usableSpace
        }
    }
}
