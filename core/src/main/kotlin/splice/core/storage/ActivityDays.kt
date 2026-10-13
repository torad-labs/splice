// NEW: V4-130, FEATURES.md 6 "Storage for the new stores" — one append-only JSONL file per UTC day,
// `<prefix>-YYYY-MM-DD.jsonl` under the state dir's `activity/` directory, the shape the contract
// chose over SQLite: no dependency, no native library, files the operator can read and delete by hand.
//
// WRITES go through JsonlSink (page-cache telemetry, torn-append heal, cross-process locking) on the
// AsyncFileIo lane, because the callers sit on the turn path and must return at once. The lane is
// best-effort by contract (a full lane drops the row and counts it), which is the right degrade for
// metadata about a turn that has already been served.
//
// DAY_MAX_BYTES IS CHOSEN AGAINST THE ROTATE: JsonlSink rolls a file over its `.1` when it would pass its
// maxBytes, and [DayGenerations.keep] moves the `.1` it found to the next number first, so no roll loses a
// row. The busiest store is the activity label store at about one row per session per 30 s: 100 sessions for
// 24 h is 288,000 rows, about 45 MB at ~150 bytes a row. 512 MB is over ten times that; the trace store rolls
// on a busy day, and its index cap (DayBodyBudget) bounds what all its generations hold.
//
// UTC DAYS, so a file's name does not depend on the daemon host's timezone or move under a DST change.
//
// RETENTION deletes whole day files older than the store's window (message edges: the
// activityRetentionDays knob, default 90; activity labels: today and yesterday, V4-285; a head's trace days:
// traceRetentionDays), WITH the day's `.lock`, every rolled generation and trace `.bodies` and `.bodies2` packs.
// One stable `<prefix>.days.lock` excludes appends and purges and is never unlinked by either. Reads
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
// FAILURES ARE SAID, NEVER READ AS EMPTY (V4-286). A directory that exists and cannot be listed, or a
// day that cannot be read, throws the IOException that says why; only a file that is not there reads
// as no lines. A day is decoded leniently, as JsonlSink.readTailAt reads: one character a disk-full
// append cut short costs its own line, where the strict decode lost the whole day. A purge answers
// what it deleted and what it could not ([DayPurge]). A sweep that fails is tried again at the next
// one, and the next midnight is armed whatever the sweep did. Each wait for it lasts at most
// MAX_SWEEP_WAIT_MS, because the timer counts only time the machine is awake.
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
import java.io.IOException
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

/** The content-addressed body pack shared by both JSONL generations of a trace day. */
public const val DAY_BODY_SUFFIX: String = ".bodies"

/** The zstd-framed body pack that replaced [DAY_BODY_SUFFIX] on 2026-10-05, in its own file so a jar from before it
 *  never opens it; a day's `.bodies` pack is only read from then on. */
public const val DAY_BODY_V2_SUFFIX: String = ".bodies2"

// why: 512 MiB, chosen against JsonlSink's rotate rather than against disk — the file header
// above states the contract: JsonlSink rolls ONE generation away when a day file passes this.
private const val DAY_MAX_BYTES = 512L shl 20

// why: a floor under the wait for the next midnight sweep. A run that starts a moment before
// midnight (the wait is monotonic, the day is wall time) re-arms for the few ms left; a clock that
// stands still just before midnight would re-arm that way forever, so no two runs are closer than this.
private const val MIN_SWEEP_GAP_MS = 1_000L

// why: the longest one wait for the midnight sweep (V4-286). The timer waits on System.nanoTime, which
// stops while the machine sleeps, so a laptop asleep across midnight swept up to a day late; a wait
// that ends early rereads the wall clock and waits again, so a slept midnight is swept this soon after waking.
private const val MAX_SWEEP_WAIT_MS = 10 * 60_000L

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

/** Encodes a queued record for its actual day file, so body companions and its JSONL index agree. */
public fun interface DayRecord {
    public fun encode(file: Path): ByteArray
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
    /** The longest one wait for the midnight sweep: MAX_SWEEP_WAIT_MS, shorter only in a test. */
    private val maxSweepWaitMs: Long = MAX_SWEEP_WAIT_MS,
    /** When given, the window is asked at every use and [retentionDays] is only what a store without it keeps. */
    private val live: RetentionDays? = null,
) {
    private val files = DayFiles(dir, prefix)
    private val deleted = DayDeleteMarker(dir, prefix, ownerOnly)
    private val sweptFor = AtomicReference<LocalDate?>(null)
    private val armed = AtomicBoolean(false)

    init {
        val now = clock()
        sweep(day(now))
        armMidnightSweep(now)
    }

    /** Queues [line] for today's file. Returns at once; never throws. */
    public fun append(line: String): Unit = append(DayRecord { (line + "\n").toByteArray(Charsets.UTF_8) })

    /** Queues an immutable record, encoded only when its day is ready on the file lane. */
    public fun append(record: DayRecord) {
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
                    files.mutation.withLock {
                        JsonlSink.appendLine(
                            file,
                            record.encode(file),
                            DAY_MAX_BYTES,
                            DayGenerations.keep,
                            JsonlSink.PAGE_CACHE_FORCE,
                        )
                        deleted.clear()
                    }
                    if (sweptFor.getAndSet(today) != today) sweep(today)
                },
                "a best-effort metadata row on the file lane; AsyncFileIo counts lane drops, and a failed " +
                    "append must not surface on the turn that produced it",
            )
        }
    }

    /** Every line of every retained day, oldest day first. A day file is small (see DAY_MAX_BYTES's
     *  arithmetic for the busiest store). A directory that cannot be listed or a day that cannot be
     *  read throws the IOException that says why, so an empty answer is an empty store (V4-286). */
    @Throws(IOException::class)
    public fun lines(): Sequence<String> = files.linesFrom(oldestKept(day(clock())))

    /** Retained generations in the same oldest-first order as [lines], using this store's clock. */
    @Throws(IOException::class)
    public fun retainedFiles(): List<Path> = files.files(oldestKept(day(clock()))).asReversed()

    /** The row-exact cut over this store's days (Oct 10, 2026): the same family as [sweep], which
     *  drops whole days the window has passed, for a save that has to delete exactly what a person
     *  was shown. Handed out here because this store's directory and prefix live here. */
    public fun cut(): DayLinesKept = DayLinesKept(dir, prefix)

    /** Deletes day files older than the retention window, relative to [today]. */
    private fun sweep(today: LocalDate) = Cancellables.discard(
        Cancellables.runCatchingCancellable { files.deleteBefore(oldestKept(today)) },
        "a directory that cannot be listed now is swept again at the next midnight, the next start and the " +
            "first append of a new day, and reads already leave out every day past the window",
    )

    /** Queues the next sweep for the UTC midnight after [now], once; each run reads the clock once,
     *  sweeps that day and arms the next midnight from the same reading, so an idle store drops a day
     *  the moment it leaves the window. A wait ends by [maxSweepWaitMs] and the run arms again, since the
     *  timer does not count time asleep. The next run is armed whatever this sweep did (V4-286: one
     *  failure ended them). A task the lane refuses leaves the store unarmed, and the next append arms
     *  it again. */
    private fun armMidnightSweep(now: Long) {
        if (!armed.compareAndSet(false, true)) return
        val midnight = day(now).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        MidnightSweeps.after(maxOf(minOf(midnight - now, maxSweepWaitMs), MIN_SWEEP_GAP_MS), armed) {
            armed.set(false)
            val at = clock()
            try {
                sweep(day(at))
            } finally {
                armMidnightSweep(at)
            }
        }
    }

    /** Today counts as one of the retained days, so a window of N keeps today and the N-1 before. */
    private fun oldestKept(today: LocalDate): LocalDate = today.minusDays((live?.days() ?: retentionDays).toLong() - 1)

    private fun day(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
}

/** A day file's name: its store's prefix, then the UTC day. */
private val DAY_FILE = Regex(
    "(.+)-\\d{4}-\\d{2}-\\d{2}\\.jsonl(?:\\.\\d+|\\.lock|\\.bodies2?|\\.bodies-evicted(?:\\.tmp)?)?",
)

/** V4-260: the [ActivityDays] stores that have day files in [dir], by prefix. For the trace dir that
 *  is every head with trace days on disk, so the days of a head splice.toml no longer names can go. */
public class DayFileStores(private val dir: Path) {
    /** None when [dir] does not exist; a directory that cannot be listed throws why (V4-286). */
    @Throws(IOException::class)
    public fun prefixes(): Set<String> =
        DirectoryEntries.of(dir)
            .mapNotNull { file -> DAY_FILE.matchEntire(file.fileName.toString())?.groupValues?.get(1) }
            .toSet()
}
