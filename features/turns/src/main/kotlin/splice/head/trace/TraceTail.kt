// NEW: V4-338 — one trace read from the NEWEST line back, for TraceRows: which lines are records, which
// turns the read takes, and when it holds them whole and can stop. It keeps the records of the turns it
// takes and nothing else; with a census it also counts every turn and skipped line on the way.
//
// V4-343: a line's stamp is read off its bytes by [JsonLineShape], which decodes none of the body, and only a
// line the shape cannot vouch for is decoded whole by kotlinx, as every line was. A record torn after its
// leading fields, the usual cut of a full disk, is one of those: kotlinx rejects it, so it stays a skipped
// line and a turn only it named is not on disk, since a record no read can list is no turn to count.
package splice.head.trace

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.storage.DayLine
import splice.core.storage.LineVisit
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.head.wire.TraceKinds

// why: how far before a turn's first attempt its turn record can lie when the attempt was written late. A
// record is stamped when it is built and the file lane writes in order, so the gap is the lane's queue:
// seconds at worst, and a read past the turns it holds costs this much of the store, about a minute of lines
private const val LATE_WRITE_SLACK_MS = 60_000L

/** The fields a trace line is placed by, decoded without building the line's tree: the bodies are
 *  nearly all of a record's bytes, and a line that does not decode is not a record. */
@Serializable
private data class TraceStamp(
    val kind: JsonElement? = null,
    val turn: JsonElement? = null,
    val ts: JsonElement? = null,
    val session: JsonElement? = null,
    val attempt: JsonElement? = null,
    val attempts: JsonElement? = null,
) {
    /** When the record was built, or null when it says no number. */
    val at: Long? get() = JsonScalars.str(ts)?.toLongOrNull()

    /** The turn it belongs to, when it is a kind this reader places. A turn is made ONLY by a kind that
     *  puts a record in it: a line carrying a turn id under a kind this reader has no branch for (a
     *  foreign line, or a record kind a newer writer has) is counted and dropped, never left as a turn
     *  holding nothing for TracedTurn.first to read a column off. */
    val placedId: String?
        get() = JsonScalars.str(turn)?.takeIf { isTurnRecord || JsonScalars.str(kind) == TraceKinds.ATTEMPT }

    val isTurnRecord: Boolean get() = JsonScalars.str(kind) == TraceKinds.TURN

    /** The first record its turn wrote: its first attempt, or a turn record that made none. */
    val opens: Boolean
        get() = if (isTurnRecord) JsonScalars.str(attempts) == "0" else JsonScalars.str(attempt) == "1"
}

/** One read from the newest line back: the turns taken so far, newest first, each held until it is whole;
 *  with [census], every turn id placed and every line skipped on the way. */
internal class TraceTail(private val ask: TraceAsk, private val census: Boolean, private val json: Json) : LineVisit {
    private val taken = LinkedHashMap<String, HeldTurn>()
    private val unopened = HashSet<String>()

    /** Taken turns whose first attempt is read and whose turn record is not, with that attempt's time: the
     *  turn is still running, or its turn record was written before its first attempt (a late write on the
     *  file lane, V4-174) and lies a little further back. */
    private val unended = HashMap<String, Long>()
    private val placed = HashSet<String>()
    private var skipped = 0
    private val shape = JsonLineShape(TraceStamp.serializer().descriptor.elementNames.toSet())

    override fun line(line: DayLine): Boolean {
        val stamp = stamp(line)
        val id = stamp?.placedId
        if (stamp == null || id == null) skipped += 1 else take(id, stamp, line)
        return census || !answered(stamp?.at)
    }

    /** Every wanted turn is taken, each has its opening record, and the read at [at] is past where a late
     *  turn record of one that has none yet could lie. */
    private fun answered(at: Long?): Boolean =
        taken.size >= ask.wanted && unopened.isEmpty() &&
            unended.values.all { opened -> at != null && at < opened - LATE_WRITE_SLACK_MS }

    fun turns(): List<TracedTurn> = taken.entries.reversed().map { (id, held) -> held.turn(id) }

    fun onDisk(): Int = placed.size

    fun skipped(): Int = skipped

    private fun take(id: String, stamp: TraceStamp, line: DayLine) {
        if (census) placed += id
        val held = taken[id] ?: admit(id, stamp) ?: return
        held.add(json.parseToJsonElement(line.text()).jsonObject, stamp.isTurnRecord)
        if (stamp.isTurnRecord) unended -= id
        if (stamp.opens) {
            unopened -= id
            val opened = stamp.at
            if (!held.ended && opened != null) unended[id] = opened
        }
    }

    /** A turn met at its latest record, taken while the read still wants turns and the ask admits it. */
    private fun admit(id: String, stamp: TraceStamp): HeldTurn? {
        if (taken.size >= ask.wanted || !ask.admits(id, JsonScalars.str(stamp.session))) return null
        unopened += id
        return HeldTurn().also { taken[id] = it }
    }

    /** The line's stamp off its bytes when the shape vouches for it, its named members decoded alone by the same
     *  serializer; else the whole line decoded, as every line was before V4-343. */
    private fun stamp(line: DayLine): TraceStamp? {
        val members = shape.members(line.bytes()) ?: return decoded(line)
        val fields = JsonObject(members.mapValues { (_, raw) -> json.parseToJsonElement(raw) })
        return json.decodeFromJsonElement(TraceStamp.serializer(), fields)
    }

    private fun decoded(line: DayLine): TraceStamp? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- V4-174: a torn or foreign line is counted as skipped by the caller and shown to the operator
        Cancellables.runCatchingCancellable { json.decodeFromString(TraceStamp.serializer(), line.text()) }.getOrNull()
}

/** One taken turn's records as they were read, newest first. */
private class HeldTurn {
    private val attempts = ArrayList<JsonObject>()
    private var ending: JsonObject? = null

    val ended: Boolean get() = ending != null

    /** The newest turn record is the one kept, as it was when the day was read forward. */
    fun add(record: JsonObject, isTurnRecord: Boolean) {
        if (!isTurnRecord) attempts += record else if (ending == null) ending = record
    }

    /** The turn in the order it was written: its attempts oldest first, then its turn record. */
    fun turn(id: String): TracedTurn = TracedTurn(id, attempts.asReversed().toList(), ending)
}
