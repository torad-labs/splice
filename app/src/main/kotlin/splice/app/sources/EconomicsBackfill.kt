// NEW: Oct 10, 2026 — the hours splice holds the turns for but has no hourly total for are built from
// those turns, so Usage reaches as far back as the request records do (Marlin: "If splice holds a
// turn, Usage counts it").
//
// THE GAP THIS CLOSES. The hourly rollup keeps sums written as each turn finished, trimmed to the
// history window; the request records are a separate, much larger file set with its own window. When
// the window widens, or when the rollup's file is younger than the records (a new install of a daemon
// that keeps more hours than the one before it), the rollup reaches back days less far than the turns
// on disk do. On this machine the day that happened the figures read: 8.0 days of hourly buckets, 15
// days of archived request records. The month view was drawn over the shorter one.
//
// AN HOUR IS BUILT ONLY WHEN IT CAN BE EXACT (Marlin). Four refusals, each for a reason a person
// would recognise as "then don't show me a number":
//  · the read was not whole — a read error — so no hour in it can be trusted to hold all its turns.
//    Nothing is backfilled, and the next read tries again.
//  · an hour that a line the reader could not parse may belong to (TORN LINES, below).
//  · the OLDEST hour the rows reach. A generation may begin mid-hour, because the rotation before the
//    archive existed discarded what came earlier, so that hour's sums would be a part read as a whole.
//  · an hour the rollup already has. It was summed from each turn's own usage as the turn finished,
//    which is closer to the truth than any rebuild; the recorded hour always wins.
//
// ONCE PER WINDOW EDGE. A successful pass closes the gap by writing the hours into the rollup's own
// file, so the next read is the file and not a scan of every archived generation in the window. It
// remembers the edge it closed the gap down to rather than that it ran: widening the window moves
// that edge back and opens a new gap, which is scanned when it opens and not at the next restart. A
// pass that could not read whole remembers nothing and is retried, because the read error may be a
// write that had not settled yet.
//
// ACROSS RESTARTS (Oct 22, 2026). The edge lived only in memory, and the rollup can never reach the window's edge by
// itself: the oldest, partial hour of the records is dropped, and the records reach back less far than the window
// (claudex 20.6 days of 35). So every boot decoded every in-window request record again (1.10 GB / 1.02 M rows for
// claudex, 1.34 GB / 0.85 M for claude-splice), taking live heap from 0.7 to 2.0 GB and committed heap to 4.9 GB in
// the 30 s after the 2:21 PM and 6:01 PM CT boots. The edge is now kept on disk ([BackfillEdgeFile]).
//
// TORN LINES (Oct 23, 2026). One unparseable line anywhere in the window refused the whole pass, and the refusal lapsed
// whenever the set of files changed. So after every rotation, about once a day per head, the next read decoded the
// whole window again to find the same tears: 1.10 GB for claudex, 1.35 GB for claude-splice, whose committed heap went
// from 1.57 to 2.56 GB at 8:56 PM CT on Oct 22. Archives are never rewritten, so a tear stays for the whole window and
// no edge was ever kept. Now a tear leaves out only the hours it could belong to, and the pass succeeds and keeps its
// edge. A tear with a leading ts costs its own hour. One with none costs the hours its row can lie in: from the newest
// row ahead of it in its file, less an hour for a row appended late, up to its archive's rotation second, or with no
// end in `.1` or the live file, whose names bound no row. A hard stop tears the live file's last line, so that costs
// the latest hours, which the rollup already holds. ProbeEconomics needs the archive bound. It reads from the oldest
// hour it grades, and a read that opens the file finds a line it cannot place, so the head's whole hourly history goes
// Unavailable (claudex, Oct 13: 944 NUL bytes in its September archive).
package splice.app.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.core.model.TurnPrice
import splice.core.perf.KeptHistory
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.head.usage.AccountOnPlan
import splice.head.usage.EconomicsBucket
import splice.head.usage.NoPlans
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

private val BACKFILL_HOUR_MS = 1.hours.inWholeMilliseconds

/** Builds the hours the rollup is missing from the turns splice still holds. */
internal class EconomicsBackfill(
    private val perf: PerfRowsFileSource,
    private val price: TurnPrice,
    private val kept: KeptHistory,
    private val clock: WallClock,
    plans: AccountOnPlan = NoPlans,
) {
    private val rows = EconomicsFromRows(price, plans)
    private val saved = BackfillEdgeFile(perf.files.stateFile("backfill-edge"))

    init {
        // Until Oct 23, 2026 a pass refused for a torn row was kept in this file, so a boot did not read the log again.
        // No pass is refused for a tear now (TORN LINES, above), and the file left with its old time would read as a
        // refusal still standing to whoever looks at the head's state.
        try {
            Files.deleteIfExists(perf.files.stateFile("backfill-refusal"))
        } catch (_: IOException) {
            // Nothing reads it; a later start removes it.
        }
    }

    /** The earliest cutoff a pass has already covered, or null when none has, kept across restarts. A moment and not
     *  a flag: a pass closes the gap down to the window's edge AS IT STOOD, and widening the window moves that edge
     *  further back and opens a new gap. */
    @Volatile
    private var edge: BackfillEdge? = saved.load()

    /**
     * The hours to add to [held], or empty when there are none to add, when the gap is already
     * closed, or when the rows cannot give an exact answer. [held] is what the rollup holds now.
     */
    fun missing(held: List<EconomicsBucket>): List<EconomicsBucket> {
        val from = kept.now().cutoffMs(clock()) ?: 0L
        val oldestHeld = held.minOfOrNull { it.hour }
        if (done(from, oldestHeld)) return emptyList()
        val evidence = exact(from) ?: return emptyList()
        val added = built(evidence, from, oldestHeld)
        cover(from, listOfNotNull(oldestHeld, added.firstOrNull()?.hour).minOrNull())
        return added
    }

    /** The evidence from [from], or null when the read was not whole. A torn line does not refuse it: [built] leaves
     *  out the hours that line could belong to. */
    private fun exact(from: Long): EconomicsPerfEvidence? =
        perf.economicsEvidence(from).takeIf { it.work.readError == null }

    /**
     * Whether a pass at [from] has nothing left to find.
     *
     * Two ways. The rollup already reaches that edge itself, so there is no gap and nothing to scan
     * the archived generations for. Or a pass has already covered that edge — ONCE PER EDGE, not
     * once per start: a wider window is an edge further back than any pass has covered, and the gap
     * it opens, between the hours the rollup reaches and the older ones the records still hold, is
     * scanned when it opens. The flag this replaced went down for good on the first pass, so
     * widening rebuilt nothing until the daemon restarted and the month view was drawn over the
     * shorter history (found in review, Oct 10, 2026).
     */
    private fun done(from: Long, oldestHeld: Long?): Boolean {
        if (oldestHeld != null && oldestHeld <= from) return true
        val covered = edge ?: return false
        return from >= covered.from && stillReaches(covered, from, oldestHeld)
    }

    /**
     * Whether the rollup still holds what the covering pass left in it: it reaches back as far as it did then, or to
     * the window's edge since, which trims it from behind. A rollup cleared, or rebuilt younger, has lost the hours the
     * pass wrote, so its gap is open again; an edge kept on disk must not outlive the hours it vouches for.
     */
    private fun stillReaches(covered: BackfillEdge, from: Long, oldestHeld: Long?): Boolean =
        oldestHeld != null && oldestHeld <= maxOf(covered.reach, from) + BACKFILL_HOUR_MS

    /** Record that a pass closed the gap down to [from], leaving the rollup's oldest hour at [reach]. A pass runs only
     *  when no kept edge covers [from], so its edge replaces the kept one. A rollup with no hours at all vouches for
     *  nothing, so no edge is kept and the next read scans again. */
    private fun cover(from: Long, reach: Long?) {
        val next = reach?.let { BackfillEdge(from, it) }
        edge = next
        if (next != null) saved.save(next) else saved.clear()
    }

    /** One bucket per hour of rows inside the gap, each priced at the cards splice holds now. */
    private fun built(
        evidence: EconomicsPerfEvidence,
        from: Long,
        oldestHeld: Long?,
    ): List<EconomicsBucket> {
        // The same rows ProbeEconomics expects a recorded hour to EQUAL, selected by the same
        // reader. An hour built from a different set of rows than the one that grades it reads as a
        // disagreement, and the console then answers Unavailable for that head's whole hourly
        // history rather than for the hour: a legacy probe left out, or a budget refusal that never
        // reached a provider left in, is enough to hide a month.
        val byHour = EconomicsRows().recorded(evidence)
            .groupBy { it.ts / BACKFILL_HOUR_MS * BACKFILL_HOUR_MS }
        val partial = byHour.keys.minOrNull()
        return byHour
            .filterKeys { hour -> hour != partial && hour >= from && (oldestHeld == null || hour < oldestHeld) }
            .filterKeys { hour -> !torn(evidence, hour) }
            .map { (hour, hourRows) -> rows.bucket(hour, hourRows) }
            .sortedBy { it.hour }
    }

    /** Whether a line the read could not parse may belong to [hour], so its rows may not be all its turns: a tear whose
     *  leading ts is in it, or one with no ts whose row can lie in it. */
    private fun torn(evidence: EconomicsPerfEvidence, hour: Long): Boolean = evidence.tears.any { tear ->
        tear.ts?.let { it / BACKFILL_HOUR_MS * BACKFILL_HOUR_MS == hour } ?: mayHold(tear.span, hour)
    }

    /** Whether [hour] ends after the earliest time a row torn in [span] can carry, and a read from it opens it. */
    private fun mayHold(span: PerfTearSpan, hour: Long): Boolean =
        (span.notBefore ?: hour) < hour + BACKFILL_HOUR_MS && perf.files.opens(span.rotatedAt, hour)
}

/** A covered edge as it is kept on disk: the window edge a pass closed the gap down to, and the oldest hour the
 *  rollup held once that pass's hours were written into it. */
@Serializable
internal data class BackfillEdge(val from: Long, val reach: Long)

/** The covered edge across restarts. An unreadable or missing file is no edge, so the next read decides again. */
internal class BackfillEdgeFile(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): BackfillEdge? = try {
        json.decodeFromString(BackfillEdge.serializer(), Files.readString(path))
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Best effort: an edge that cannot be kept is scanned for again after the next restart, as before. */
    fun save(edge: BackfillEdge) {
        try {
            SecureFile.writeAtomic0600(path, json.encodeToString(BackfillEdge.serializer(), edge))
        } catch (_: IOException) {
            // The edge still stands in memory for this run.
        }
    }

    /** Best effort: an edge that cannot be removed is judged against the rollup again at the next start. */
    fun clear() {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // stillReaches() still refuses it unless the rollup holds the hours it vouches for.
        }
    }
}
