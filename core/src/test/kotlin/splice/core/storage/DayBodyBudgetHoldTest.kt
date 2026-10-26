// The gate on DayBodyBudget's lock hold (Oct 26, 2026): inside one hold of the directory lock, admissions may share
// one listing of the trace directory, moved only by the hold's own grants and relisted after its own deletions. Each
// case builds two generated trace directories, and builds the pair twice: two or three heads over five UTC days,
// rolled index generations, v2 and v1 packs, eviction markers, locks, an undated pack and a file named for an
// impossible date. One generated sequence of body and index admissions then runs over each copy, and every grant is
// followed by exactly the granted bytes, as the trace writer writes them. Run A asks inside one hold of the first
// directory, run B gives each call a hold of its own. A hold changes nothing a caller can see, so the two must decide
// every call alike and leave the same files, and neither may let a directory's dated packs pass the body cap or its
// dated index files pass the index cap.
// Oct 26, 2026, on review: some calls are for the second directory, and run A makes them inside its hold of the
// first. Such a call must list its own directory, and what it grants or deletes must not move the hold's listing.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonlForce
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.random.Random

// why: the property replays: a red names its case and step, and the same seed rebuilds both
private const val SEED = 20_261_026L

// why: enough cases that every outcome below is met many times over; each directory holds a few dozen tiny files
private const val CASES = 100

// why: admissions per case, so one hold grants, evicts and removes days more than once
private const val STEPS = 32

// why: the least each outcome gets across the cases, so a generator that stops reaching one fails here rather than
// passing a property that tested nothing
private const val OUTCOME_FLOOR = 50

// why: the free-space floor every case keeps
private const val FLOOR = 64L

// why: the heads a case takes two or three of
private val HEADS = listOf("claude", "claudex", "kimi")

// why: run A's hold is opened on the held directory, and some of the calls made inside it are for the other one
private const val HELD_DIR = "held"
private const val OTHER_DIR = "other"
private val DIRS = listOf(HELD_DIR, OTHER_DIR)

// why: the five UTC days every generated directory spans; the clock starts late on the last of them
private val DATES = (1..5).map { LocalDate.of(2026, 10, it) }

// why: the clock starts up to five minutes before midnight UTC on the last day and moves up to 20 s a step, so about
// half the cases cross into the next day partway
private val START = Instant.parse("2026-10-05T23:55:00Z").toEpochMilli()
private const val START_SPREAD_MS = 300_000L
private const val STEP_MAX_MS = 20_000L

// why: tiny caps and files, so a few dozen admissions fill both caps several times
private const val BODY_CAP_MIN = 1_500L
private const val BODY_CAP_MAX = 6_000L
private const val INDEX_CAP_MIN = 1_000L
private const val INDEX_CAP_MAX = 5_000L
private const val PACK_FILE_MAX = 1_200L
private const val INDEX_FILE_MAX = 800L
private const val CHUNK_MAX_BYTES = 600L
private const val LINE_MAX_BYTES = 400L

// why: free space while a step does not probe the floor: far above every cap, and nowhere near an overflow
private const val PLENTY = Long.MAX_VALUE / 4

// why: every generated choice below is a share of twenty
private const val ODDS = 20
private const val LIVE_SHARE = 17
private const val ROLLED_SHARE = 8
private const val OLDER_SHARE = 5
private const val V2_SHARE = 12
private const val V1_SHARE = 3
private const val MARKER_SHARE = 2
private const val BODY_SHARE = 12

// why: how often a call is for the other directory; the rest stay on the held one, whose listing the hold keeps
private const val OTHER_SHARE = 4

// why: how often a line, and a body, is asked for with the volume near the floor; a body below it evicts every
// completed day before it is refused, so it comes less often and leaves the cases older days to evict
private const val LOW_FREE_SHARE = 3
private const val LOW_FREE_BODY_SHARE = 1

// why: what a granted call reports; a refusal reports its reason
private const val GRANTED = "granted"

// why: the refusals the budget documents, by their reasons
private const val BODY_CAP = "refused: shared rolling trace body budget exhausted"
private const val FLOOR_REACHED = "refused: trace volume free-space floor reached"
private const val INDEX_CAP = "refused: shared trace index budget exhausted by the day being written"

// why: a day file's name, `<head>-<yyyy-MM-dd>.jsonl`, then what may follow it: a rolled generation or a body pack
private val DAY_NAME = Regex("(.+)-(\\d{4}-\\d{2}-\\d{2})\\.jsonl(\\.\\d+|\\.bodies2?)?")

private val MARKER = (BODY_BUDGET_EVICTED_REASON + "\n").toByteArray()

class DayBodyBudgetHoldTest {
    @Test
    fun `one hold over calls for its directory and another decides each as a hold per call would and passes no cap`(
        @TempDir root: Path,
    ) {
        val random = Random(SEED)
        val counts = HoldOutcome.entries.associateWith { 0 }.toMutableMap()
        repeat(CASES) { n ->
            val case = HoldCase(random)
            val one = run(Files.createDirectory(root.resolve("one-$n")), case, oneHold = true)
            val each = run(Files.createDirectory(root.resolve("each-$n")), case, oneHold = false)
            case.admissions.forEachIndexed { i, admission ->
                val step = "case $n step $i: $admission"
                assertEquals(each[i + 1].outcome, one[i + 1].outcome, "$step decides as a hold of its own would")
                assertEquals(each[i + 1].files, one[i + 1].files, "$step leaves the files a hold of its own would")
                for (decisions in listOf(one, each)) capped(case, admission, decisions[i + 1], step)
                outcomes(admission, one[i + 1].outcome, one[i].files, one[i + 1].files, case.start.keys)
                    .forEach { counts[it] = counts.getValue(it) + 1 }
            }
            listOf("one-$n", "each-$n").forEach { remove(root.resolve(it)) }
        }
        val table = counts.entries.joinToString { (outcome, n) -> "$outcome $n" }
        counts.forEach { (outcome, n) -> assertTrue(n >= OUTCOME_FLOOR) { "too few $outcome to test: $table" } }
    }

    /** [case] in two directories under [root]: inside one hold of the held directory when [oneHold], else in a hold
     *  per call. The first entry is the directories as built, then what each admission decided and the files it
     *  left. */
    private fun run(root: Path, case: HoldCase, oneHold: Boolean): List<HoldDecision> {
        DIRS.forEach { Files.createDirectory(root.resolve(it)) }
        case.start.forEach { (name, bytes) ->
            val file = root.resolve(name)
            if (name.endsWith(DAY_BODY_EVICTED_SUFFIX)) Files.write(file, MARKER) else Files.write(file, zeros(bytes))
        }
        val built = HoldDecision("built", files(root))
        var free = PLENTY
        var now = START
        val budget = DayBodyBudget(
            case.bodyCap,
            FLOOR,
            DayVolumeSpace { free },
            WallClock { now },
            JsonlForce { _, _ -> },
            indexMaxBytes = case.indexCap,
        )
        val ask = { admission: HoldAdmission ->
            free = admission.free
            now = admission.now
            val dir = root.resolve(admission.dir)
            // Every writer takes its directory's lock first. Inside run A's hold this nests and opens no hold of its
            // own, so a call for the other directory is made while the held directory's hold is open.
            HoldDecision(budget.withLock(dir) { decide(budget, dir, admission) }, files(root))
        }
        val decided = if (oneHold) {
            budget.withLock(root.resolve(HELD_DIR)) { case.admissions.map(ask) }
        } else {
            case.admissions.map(ask)
        }
        return listOf(built) + decided
    }

    /** Asks [budget] for [admission] in [dir] and, once it is granted, writes exactly the granted bytes at the end of
     *  the pack or day file, as the trace writer does. What the budget decided. */
    private fun decide(budget: DayBodyBudget, dir: Path, admission: HoldAdmission): String {
        val file = dir.resolve(admission.name)
        val refused = try {
            if (admission.body) {
                budget.admit(file, admission.bytes)
                null
            } else {
                budget.admitIndex(file, admission.bytes)
            }
        } catch (capacity: DayBodyCapacityException) {
            capacity.reason
        } catch (failure: IOException) {
            return "threw ${failure.javaClass.name}"
        }
        if (refused != null) return "refused: $refused"
        Files.write(file, zeros(admission.bytes), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        return GRANTED
    }

    /** What one admission showed, read from its outcome and from the files before and after it. */
    private fun outcomes(
        admission: HoldAdmission,
        outcome: String,
        before: Map<String, Long>,
        after: Map<String, Long>,
        built: Set<String>,
    ): List<HoldOutcome> {
        val deleted = (before.keys - after.keys).isNotEmpty()
        val shown = when {
            admission.dir == OTHER_DIR -> mapOf(
                HoldOutcome.OTHER_DIRECTORY_GRANT to (outcome == GRANTED),
                HoldOutcome.OTHER_DIRECTORY_DELETION to deleted,
                HoldOutcome.OTHER_DIRECTORY_CAP_REFUSAL to (outcome == BODY_CAP || outcome == INDEX_CAP),
            )
            admission.body -> {
                // A pack an earlier grant of the case created, which this step's grant now adds to.
                val key = "${admission.dir}/${admission.name}"
                val created = key !in built && key in before && dated(key, pack = true)
                mapOf(
                    HoldOutcome.BODY_GRANT to (outcome == GRANTED),
                    HoldOutcome.EVICTION to deleted,
                    HoldOutcome.BODY_CAP_REFUSAL to (outcome == BODY_CAP),
                    HoldOutcome.GRANT_INTO_CREATED_PACK to (outcome == GRANTED && created),
                    HoldOutcome.FLOOR_REFUSAL to (outcome == FLOOR_REACHED),
                )
            }
            else -> mapOf(
                HoldOutcome.INDEX_GRANT to (outcome == GRANTED),
                HoldOutcome.DAY_REMOVAL to deleted,
                HoldOutcome.INDEX_CAP_REFUSAL to (outcome == INDEX_CAP),
                HoldOutcome.FLOOR_REFUSAL to (outcome == FLOOR_REACHED),
            )
        }
        return shown.filterValues { it }.keys.toList()
    }

    /** A granted body leaves its directory's dated packs within the body cap, and a granted line leaves its directory's
     *  dated index files within the index cap; a directory a case starts from may hold more than either. */
    private fun capped(case: HoldCase, admission: HoldAdmission, decision: HoldDecision, step: String) {
        if (decision.outcome != GRANTED) return
        val own = decision.files.filterKeys { it.startsWith("${admission.dir}/") }
        if (admission.body) {
            assertTrue(packBytes(own) <= case.bodyCap) { "$step: the dated packs pass the body cap" }
        } else {
            assertTrue(indexBytes(own) <= case.indexCap) { "$step: the dated index passes its cap" }
        }
    }

    private fun packBytes(files: Map<String, Long>): Long =
        files.filterKeys { dated(it, pack = true) }.values.sum()

    private fun indexBytes(files: Map<String, Long>): Long =
        files.filterKeys { dated(it, pack = false) }.values.sum()

    /** Whether [name] is a body pack, or else an index file or one of its rolled generations, of a real UTC day. */
    private fun dated(name: String, pack: Boolean): Boolean {
        val match = DAY_NAME.matchEntire(name) ?: return false
        return match.groupValues[3].startsWith(".bodies") == pack && dateOf(match.groupValues[2]) != null
    }

    private fun dateOf(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    /** Every file of both directories under [root], named by its directory and its own name, with its size. */
    private fun files(root: Path): Map<String, Long> =
        DIRS.flatMap { dir ->
            Files.list(root.resolve(dir)).use { entries -> entries.toList() }
                .map { "$dir/${it.fileName}" to Files.size(it) }
        }.toMap().toSortedMap()

    private fun remove(root: Path) {
        for (dir in DIRS) {
            Files.list(root.resolve(dir)).use { entries -> entries.toList() }.forEach(Files::delete)
            Files.delete(root.resolve(dir))
        }
        Files.delete(root)
    }

    private fun zeros(bytes: Long): ByteArray = ByteArray(Math.toIntExact(bytes))
}

/** What the floor counts, each at least OUTCOME_FLOOR times across the cases. The first eight are calls for the held
 *  directory, the last three calls for the other one, which run A makes inside its hold of the held one. */
private enum class HoldOutcome {
    BODY_GRANT,
    EVICTION,
    BODY_CAP_REFUSAL,
    FLOOR_REFUSAL,
    INDEX_GRANT,
    DAY_REMOVAL,
    INDEX_CAP_REFUSAL,
    GRANT_INTO_CREATED_PACK,
    OTHER_DIRECTORY_GRANT,
    OTHER_DIRECTORY_DELETION,
    OTHER_DIRECTORY_CAP_REFUSAL,
}

/** One admission in the directory [dir]: a body chunk of [bytes] for the pack [name], or an index line of [bytes] for
 *  the day file [name], asked while the volume has [free] usable bytes and the clock reads [now]. */
private data class HoldAdmission(
    val dir: String,
    val body: Boolean,
    val name: String,
    val bytes: Long,
    val free: Long,
    val now: Long,
)

/** What one admission decided, and every file of both directories after it, by directory and name, with its size. */
private data class HoldDecision(val outcome: String, val files: Map<String, Long>)

/** One generated case: the two directories it starts from, each file by its directory and name with its size, its two
 *  caps and its admissions. */
private class HoldCase(random: Random) {
    val heads: List<String> = HEADS.take(random.nextInt(2, HEADS.size + 1))
    val bodyCap: Long = random.nextLong(BODY_CAP_MIN, BODY_CAP_MAX + 1)
    val indexCap: Long = random.nextLong(INDEX_CAP_MIN, INDEX_CAP_MAX + 1)
    val start: Map<String, Long> = HashMap<String, Long>().also { files ->
        for (dir in DIRS) directory(files, random, dir)
    }
    val admissions: List<HoldAdmission> = (START + random.nextLong(START_SPREAD_MS)).let { first ->
        var now = first
        List(STEPS) {
            now += random.nextLong(STEP_MAX_MS)
            admission(random, now)
        }
    }

    /** One generated trace directory, [dir]: every head's days, the directory's lock, and two files no day counts. */
    private fun directory(files: MutableMap<String, Long>, random: Random, dir: String) {
        files["$dir/directory.days.lock"] = 0L
        // A pack whose day has no date, and an index file named for a day no calendar has: neither is counted.
        files["$dir/notes.jsonl$DAY_BODY_V2_SUFFIX"] = random.nextLong(PACK_FILE_MAX)
        files["$dir/${heads.first()}-2026-02-30.jsonl"] = random.nextLong(INDEX_FILE_MAX)
        for (head in heads) {
            if (random.nextBoolean()) files["$dir/$head.days.lock"] = 0L
            for (date in DATES) day(files, random, "$dir/$head-$date.jsonl")
        }
    }

    /** One head's [day]: its index file and rolled generations, its lock, its packs and its marker, each when drawn. */
    private fun day(files: MutableMap<String, Long>, random: Random, day: String) {
        if (random.nextInt(ODDS) < LIVE_SHARE) files[day] = random.nextLong(INDEX_FILE_MAX)
        if (random.nextInt(ODDS) < ROLLED_SHARE) files["$day$NEWEST_ROLLED"] = random.nextLong(INDEX_FILE_MAX)
        if (random.nextInt(ODDS) < OLDER_SHARE) files["$day.2"] = random.nextLong(INDEX_FILE_MAX)
        if (random.nextBoolean()) files["$day.lock"] = 0L
        if (random.nextInt(ODDS) < V2_SHARE) files["$day$DAY_BODY_V2_SUFFIX"] = random.nextLong(PACK_FILE_MAX)
        if (random.nextInt(ODDS) < V1_SHARE) files["$day$DAY_BODY_SUFFIX"] = random.nextLong(PACK_FILE_MAX)
        if (random.nextInt(ODDS) < MARKER_SHARE) files["$day$DAY_BODY_EVICTED_SUFFIX"] = MARKER.size.toLong()
    }

    /** A body chunk for one of the day file's packs, or an index line for the day file itself, in the held directory
     *  or now and then the other one. */
    private fun admission(random: Random, now: Long): HoldAdmission {
        val dir = if (random.nextInt(ODDS) < OTHER_SHARE) OTHER_DIR else HELD_DIR
        val day = dayFile(random, now)
        val body = random.nextInt(ODDS) < BODY_SHARE
        val bytes = random.nextLong(1, (if (body) CHUNK_MAX_BYTES else LINE_MAX_BYTES) + 1)
        val low = random.nextInt(ODDS) < if (body) LOW_FREE_BODY_SHARE else LOW_FREE_SHARE
        val free = if (low) random.nextLong(FLOOR + 2 * bytes) else PLENTY
        val name = if (body) packOf(random, day) else day
        return HoldAdmission(dir, body, name, bytes, free, now)
    }

    /** A day file's name, mostly for today or yesterday by [now], sometimes for any day of the directory, and now
     *  and then for a day with no date or one no calendar has. */
    private fun dayFile(random: Random, now: Long): String {
        val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        val date = when (random.nextInt(4)) {
            0, 1 -> today
            2 -> today.minusDays(1)
            else -> DATES.random(random)
        }
        val head = heads.random(random)
        return when (random.nextInt(ODDS)) {
            0 -> "notes.jsonl"
            1 -> "$head-2026-02-30.jsonl"
            else -> "$head-$date.jsonl"
        }
    }

    /** The v1 or, more often, the v2 pack of [day]. */
    private fun packOf(random: Random, day: String): String =
        if (random.nextInt(ODDS) < V1_SHARE) "$day$DAY_BODY_SUFFIX" else "$day$DAY_BODY_V2_SUFFIX"
}
