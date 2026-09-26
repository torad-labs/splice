// NEW: V4-130, FEATURES.md 6 "Storage for the new stores" — one append-only JSONL file per UTC day,
// `<prefix>-YYYY-MM-DD.jsonl` under the state dir's `activity/` directory, the shape the contract
// chose over SQLite: no dependency, no native library, files the operator can read and delete by hand.
//
// WRITES go through JsonlSink (per-row fsync, torn-append heal, the cross-process lock) on the
// AsyncFileIo lane, because the callers sit on the turn path and must return at once. The lane is
// best-effort by contract (a full lane drops the row and counts it), which is the right degrade for
// metadata about a turn that has already been served.
//
// DAY_MAX_BYTES IS CHOSEN AGAINST THE ROTATE, per the contract: JsonlSink rolls ONE generation away when
// a file would pass its maxBytes, and a rolled day is a day lost. The busiest store is the activity
// label store at about one row per session per 30 s: 100 sessions for 24 h is 288,000 rows, about
// 45 MB at ~150 bytes a row. 512 MB is over ten times that, so a day is never rolled away short of a
// runaway writer, which the rotate then bounds.
//
// UTC DAYS, so a file's name does not depend on the daemon host's timezone or move under a DST change.
//
// RETENTION deletes whole day files older than the store's window (message edges: the
// activityRetentionDays knob, default 90; activity labels: today only, V4-261; a head's trace days:
// traceRetentionDays), WITH JsonlSink's two siblings of that file: its cross-process `.lock` and a
// rolled `.1` generation. Leaving them would leak one lock file per store per day forever. Reads
// ignore files older than the window too, so an unswept file never re-enters a view.
//
// WHEN A DAY GOES (V4-273): at the store's open, at each UTC midnight after it, and on the first
// write of each new day. The sweep used to run only inside that write, so a store that wrote nothing
// kept every day past its window on disk: yesterday's labels, edges past 90 days, trace days past
// their retention. The wait for each midnight is on its own timer ([MidnightSweeps]), not on the
// file lane, whose pending cap is for work that is due: a day-long wait there held one of its slots
// all day, per store. At midnight the sweep runs on the lane, in order with the store's appends
// (no append lands in a day file the sweep is deleting), and arms the next. A lane that refuses it
// leaves the store unarmed until its next append arms it again.
//
// ONLY THE STORE THAT WRITES OWNS A WINDOW. A reader of every day on disk (`splice trace`) and a
// purge of all of a store's days go through [DayFiles], which has no window and never sweeps: a
// stand-in window there would be swept on (a purge's window of 1, a reader's century).
package splice.core.storage

import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.FileIoTask
import splice.core.util.JsonlSink
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The state-dir subdirectory every activity store writes under. */
public const val ACTIVITY_DIRECTORY: String = "activity"

// why: 512 MiB, chosen against JsonlSink's rotate rather than against disk — the file header
// above states the contract: JsonlSink rolls ONE generation away when a day file passes this.
private const val DAY_MAX_BYTES = 512L shl 20

/** A day file's own name, then JsonlSink's lock and its one rolled generation beside it. */
/** JsonlSink's rotated generation of a day file: that day's OLDER rows, once it passed DAY_MAX_BYTES. */
private const val ROLLED_SUFFIX = ".1"
private val DAY_SIBLINGS = listOf("", ".lock", ROLLED_SUFFIX)

// why: a floor under the wait for the next midnight sweep. A run that starts a moment before
// midnight (the wait is monotonic, the day is wall time) re-arms for the few ms left; a clock that
// stands still just before midnight would re-arm that way forever, so no two runs are closer than this.
private const val MIN_SWEEP_GAP_MS = 1_000L

/** V4-273: the waits for every store's midnight sweep, on one named daemon thread. It holds only the
 *  wait; the sweep itself is handed to the file lane when its midnight comes. */
private object MidnightSweeps {
    // The platform factory, as AsyncFileIo's lane: this timer owns only its name and daemon-ness.
    private val threads = Executors.defaultThreadFactory()
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        threads.newThread(task).apply {
            name = "splice-day-sweep"
            isDaemon = true
        }
    }

    /** Hands [sweep] to the file lane after [delayMs]; a lane that refuses it then clears [armed]. */
    fun after(delayMs: Long, armed: AtomicBoolean, sweep: FileIoTask) {
        val handOver = Runnable { if (!AsyncFileIo.submit(task = sweep)) armed.set(false) }
        val _ = timer.schedule(handOver, delayMs, TimeUnit.MILLISECONDS)
    }
}

public class ActivityDays(
    private val dir: Path,
    private val prefix: String,
    private val retentionDays: Int,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    /** V4-174: true for a store whose lines carry private content (a head's trace): the day files
     *  live in an owner-only directory (SecureFile.ownerOnlyDirectory), asserted on every append so
     *  a recreated directory is never left at the umask's default. The activity stores keep the
     *  plain directory they always had. */
    private val ownerOnly: Boolean = false,
) {
    private val files = DayFiles(dir, prefix)
    private val sweptFor = AtomicReference<LocalDate?>(null)
    private val armed = AtomicBoolean(false)

    init {
        val now = clock()
        sweep(day(now))
        armMidnightSweep(now)
    }

    /** Queues [line] for today's file. Returns at once; never throws. */
    public fun append(line: String) {
        val now = clock()
        armMidnightSweep(now)
        val today = day(now)
        val file = dir.resolve("$prefix-$today.jsonl")
        AsyncFileIo.submit {
            Cancellables.discard(
                Cancellables.runCatchingCancellable {
                    // A reason this dir stayed open is not said per append: it sits inside the state dir,
                    // which every start holds owner-only and names if it could not (secureStateDirs), and
                    // nobody else can traverse into it past a 0700 parent.
                    if (ownerOnly) {
                        val _ = SecureFile.ownerOnlyDirectory(dir)
                    } else {
                        Files.createDirectories(dir)
                    }
                    JsonlSink.appendLine(file, line, DAY_MAX_BYTES)
                    if (sweptFor.getAndSet(today) != today) sweep(today)
                },
                "a best-effort metadata row on the file lane; AsyncFileIo counts lane drops, and a failed " +
                    "append must not surface on the turn that produced it",
            )
        }
    }

    /** Every line of every retained day, oldest day first. A day file is small (see DAY_MAX_BYTES's
     *  arithmetic for the busiest store), and a file that cannot be read is skipped, not fatal. */
    public fun lines(): Sequence<String> = files.linesFrom(oldestKept(day(clock())))

    /** Deletes day files older than the retention window, relative to [today]. */
    private fun sweep(today: LocalDate) = files.deleteBefore(oldestKept(today))

    /** Queues the next sweep for the UTC midnight after [now], once; each run reads the clock once,
     *  sweeps that day and arms the next midnight from the same reading, so an idle store drops a day
     *  the moment it leaves the window. A task the lane refuses leaves the store unarmed, and the next
     *  append arms it again. */
    private fun armMidnightSweep(now: Long) {
        if (!armed.compareAndSet(false, true)) return
        val midnight = day(now).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        MidnightSweeps.after(maxOf(midnight - now, MIN_SWEEP_GAP_MS), armed) {
            armed.set(false)
            val at = clock()
            sweep(day(at))
            armMidnightSweep(at)
        }
    }

    /** Today counts as one of the retained days, so a window of N keeps today and the N-1 before. */
    private fun oldestKept(today: LocalDate): LocalDate = today.minusDays(retentionDays.toLong() - 1)

    private fun day(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
}

/** V4-273: one store's day files whatever their age, with no window: every line on disk (the
 *  `splice trace` reader) and the purge of all of them. The window, and every sweep, belong to the
 *  store that writes the files ([ActivityDays]). */
public class DayFiles(private val dir: Path, prefix: String) {
    private val namePattern = Regex("${Regex.escape(prefix)}-(\\d{4}-\\d{2}-\\d{2})\\.jsonl")

    /** Every line of every day on disk, oldest day first. */
    public fun lines(): Sequence<String> = linesFrom(LocalDate.MIN)

    /** V4-174: deletes EVERY day file of this store, whatever its age, with JsonlSink's siblings —
     *  the `splice trace --purge` verb. Returns the day files that existed, so the caller can say
     *  what went; a file that could not be deleted is still listed and still on disk. */
    public fun purge(): List<Path> {
        val files = days().map { (_, file) -> file }
        files.forEach(::deleteDay)
        return files
    }

    /** Every line of the days from [oldest] on, oldest day first, each day's rolled half first. */
    internal fun linesFrom(oldest: LocalDate): Sequence<String> =
        days().filter { (date, _) -> !date.isBefore(oldest) }
            .asSequence()
            .flatMap { (_, file) -> (readRolled(file) + readLines(file)).asSequence() }

    /** Deletes every day file dated before [oldest], with its siblings. */
    internal fun deleteBefore(oldest: LocalDate) {
        for ((date, file) in days()) {
            if (date.isBefore(oldest)) deleteDay(file)
        }
    }

    /** The day file and the siblings JsonlSink writes beside it. */
    private fun deleteDay(file: Path) {
        for (suffix in DAY_SIBLINGS) {
            val sibling = file.resolveSibling("${file.fileName}$suffix")
            Cancellables.discard(
                Cancellables.runCatchingCancellable { Files.deleteIfExists(sibling) },
                "a file that cannot be deleted now is retried on the next day's sweep, and reads already ignore it",
            )
        }
    }

    private fun days(): List<Pair<LocalDate, Path>> =
        // ast-grep-ignore: kt-no-silent-result-collapse -- no directory yet means no rows yet, which is the empty answer
        Cancellables.runCatchingCancellable { Files.newDirectoryStream(dir).use { it.toList() } }
            .getOrDefault(emptyList())
            .mapNotNull { file -> dateOf(file)?.let { it to file } }
            .sortedBy { it.first }

    private fun dateOf(file: Path): LocalDate? = namePattern.matchEntire(file.fileName.toString())
        ?.groupValues?.get(1)
        // ast-grep-ignore: kt-no-silent-result-collapse -- a name that matches the pattern but is not a calendar date is not one of this store's files
        ?.let { Cancellables.runCatchingCancellable { LocalDate.parse(it) }.getOrNull() }

    /** The day's rotated older half, read BEFORE the live file so the day comes back whole and in order
     *  (v0.4.0 release: the read skipped it, so a day that rotated started halfway, unsaid). */
    private fun readRolled(file: Path): List<String> {
        val rolled = file.resolveSibling("${file.fileName}$ROLLED_SUFFIX")
        return if (Files.exists(rolled)) readLines(rolled) else emptyList()
    }

    private fun readLines(file: Path): List<String> =
        // ast-grep-ignore: kt-no-silent-result-collapse -- an unreadable day file is left out of the view rather than failing the whole read; the file is still on disk for the operator
        Cancellables.runCatchingCancellable { Files.readAllLines(file) }.getOrDefault(emptyList())
}

/** A day file's name: its store's prefix, then the UTC day. */
private val DAY_FILE = Regex("(.+)-\\d{4}-\\d{2}-\\d{2}\\.jsonl")

/** V4-260: the [ActivityDays] stores that have day files in [dir], by prefix. For the trace dir that
 *  is every head with trace days on disk, so the days of a head splice.toml no longer names can go. */
public class DayFileStores(private val dir: Path) {
    public fun prefixes(): Set<String> =
        // ast-grep-ignore: kt-no-silent-result-collapse -- no directory yet means no day files; an unreadable one is listed again at the next start
        Cancellables.runCatchingCancellable { Files.newDirectoryStream(dir).use { it.toList() } }
            .getOrDefault(emptyList())
            .mapNotNull { file -> DAY_FILE.matchEntire(file.fileName.toString())?.groupValues?.get(1) }
            .toSet()
}
