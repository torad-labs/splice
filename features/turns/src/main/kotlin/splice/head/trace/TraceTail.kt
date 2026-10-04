// NEW: V4-338 — one trace read from the NEWEST line back, for TraceRows: which lines are records, which
// turns the read takes, and when it holds them whole and can stop. It keeps the records of the turns it
// takes and nothing else.
//
// V4-343: a line's stamp is read off its bytes ([TraceStamps]). The count of every turn and skipped line on
// disk was a flag on this read, which then read every line of every day; it is [TraceCensus]'s now, which
// keeps what it counted between reads, so this read stops once it holds its turns, as the one-turn read did.
package splice.head.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.core.memory.HeapText
import splice.core.storage.DayLine
import splice.core.storage.LineVisit
import splice.core.util.JsonScalars
import splice.head.trace.body.TraceBodies
import splice.head.trace.body.TraceBodyReaders
import splice.upstream.memory.JvmHeap

// why: how far before a turn's first attempt its turn record can lie when the attempt was written late. A
// record is stamped when it is built and the file lane writes in order, so the gap is the lane's queue:
// seconds at worst, and a read past the turns it holds costs this much of the store, about a minute of lines
private const val LATE_WRITE_SLACK_MS = 60_000L

/** One read from the newest line back: the turns taken so far, newest first, each held until it is whole. */
internal class TraceTail(
    private val ask: TraceAsk,
    private val json: Json,
    private val heap: HeapBudget = JvmHeap.budget,
) : LineVisit, AutoCloseable {
    private val taken = LinkedHashMap<String, HeldTurn>()
    private val unopened = HashSet<String>()

    /** Taken turns whose first attempt is read and whose turn record is not, with that attempt's time: the
     *  turn is still running, or its turn record was written before its first attempt (a late write on the
     *  file lane, V4-174) and lies a little further back. */
    private val unended = HashMap<String, Long>()
    private val stamps = TraceStamps(json, heap)
    private val bodies = TraceBodies(heap = heap)
    private val readers = TraceBodyReaders(heap)

    override fun close() = readers.close()

    override fun line(line: DayLine): Boolean {
        val stamp = stamps.of(line)
        val id = stamp?.placedId
        if (stamp != null && id != null) take(id, stamp, line)
        return !answered(stamp?.at)
    }

    /** Every wanted turn is taken, each has its opening record, and the read at [at] is past where a late
     *  turn record of one that has none yet could lie. */
    private fun answered(at: Long?): Boolean =
        taken.size >= ask.wanted && unopened.isEmpty() &&
            unended.values.all { opened -> at != null && at < opened - LATE_WRITE_SLACK_MS }

    fun turns(): List<TracedTurn> = taken.entries.reversed().map { (id, held) -> held.turn(id) }

    private fun take(id: String, stamp: TraceStamp, line: DayLine) {
        val held = taken[id] ?: admit(id, stamp) ?: return
        held.add(selected(line), stamp.isTurnRecord)
        if (stamp.isTurnRecord) unended -= id
        if (stamp.opens) {
            unopened -= id
            val opened = stamp.at
            if (!held.ended && opened != null) unended[id] = opened
        }
    }

    private fun selected(line: DayLine): JsonObject {
        if (line.byteSize >= Int.MAX_VALUE) throw HeapCapacityException()
        return line.bytes().use { input ->
            HeapText.Reader.read(input, line.byteSize, heap).use { staged ->
                val record = json.parseToJsonElement(staged.text).jsonObject
                bodies.selected(record, line.file, readers).also { selected ->
                    // Hydrated strings have their own owners; only parsed row metadata survives this stage.
                    staged.retain(selected, HeapJson.bytes(record))
                }
            }
        }
    }

    /** A turn met at its latest record, taken while the read still wants turns and the ask admits it. */
    private fun admit(id: String, stamp: TraceStamp): HeldTurn? {
        if (taken.size >= ask.wanted || !ask.admits(id, JsonScalars.str(stamp.session))) return null
        unopened += id
        return HeldTurn(heap).also { taken[id] = it }
    }
}

/** One taken turn's records as they were read, newest first. */
private class HeldTurn(private val heap: HeapBudget) {
    private val attempts = ArrayList<JsonObject>()
    private val attemptLease = HeapOwners.charge(attempts, heap, HeapJson.text(""))
    private var ending: JsonObject? = null

    val ended: Boolean get() = ending != null

    /** The newest turn record is the one kept, as it was when the day was read forward. */
    fun add(record: JsonObject, isTurnRecord: Boolean) {
        if (!isTurnRecord) {
            val needed = (attempts.size + 1L) * TRACE_ATTEMPT_REFERENCE_BYTES
            if (!attemptLease.resize(needed)) throw HeapCapacityException()
            attempts += record
        } else if (ending == null) {
            ending = record
        }
    }

    /** The turn in the order it was written: its attempts oldest first, then its turn record. */
    fun turn(id: String): TracedTurn {
        val copy = heap.reserve(attempts.size * TRACE_ATTEMPT_REFERENCE_BYTES) ?: throw HeapCapacityException()
        var kept = false
        try {
            val records = attempts.asReversed().toList()
            HeapOwners.keep(records, copy)
            kept = true
            return TracedTurn(id, records, ending)
        } finally {
            if (!kept) copy.close()
        }
    }
}

// why: array references and backing-array growth for the selected attempt list.
private const val TRACE_ATTEMPT_REFERENCE_BYTES = 64L
