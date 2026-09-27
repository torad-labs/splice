// NEW: V4-343 — a trace line's stamp, the fields it is placed by, for the two reads of a head's trace: the listing
// ([TraceTail]) and the count ([TraceCensus]). It was TraceTail's own while the count was a flag on the listing's
// read; the count now keeps what it counted between reads, and both read a line through this, the same way.
//
// A line's stamp is read off its bytes by [JsonLineShape], which decodes none of the body, and only a line the
// shape cannot vouch for is decoded whole by kotlinx, as every line was before V4-343. A record torn after its
// leading fields, the usual cut of a full disk, is one of those: kotlinx rejects it, so it is no record, and a
// turn only it named is not on disk, since a record no read can list is no turn to count.
package splice.head.trace

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.storage.DayLine
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.head.wire.TraceKinds

/** The fields a trace line is placed by, decoded without building the line's tree: the bodies are
 *  nearly all of a record's bytes, and a line that does not decode is not a record. */
@Serializable
internal data class TraceStamp(
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

/** Reads lines' stamps, one line at a time: it keeps a buffer, so one read holds one. */
internal class TraceStamps(private val json: Json) {
    private val shape = JsonLineShape(TraceStamp.serializer().descriptor.elementNames.toSet())

    /** The line's stamp off its bytes when the shape vouches for it, its named members decoded alone by the same
     *  serializer; else the whole line decoded, as every line was before V4-343; null when it is no record. */
    fun of(line: DayLine): TraceStamp? {
        val members = shape.members(line.bytes()) ?: return decoded(line)
        val fields = JsonObject(members.mapValues { (_, raw) -> json.parseToJsonElement(raw) })
        return json.decodeFromJsonElement(TraceStamp.serializer(), fields)
    }

    private fun decoded(line: DayLine): TraceStamp? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- V4-174: a torn or foreign line is counted as skipped by the caller and shown to the operator
        Cancellables.runCatchingCancellable { json.decodeFromString(TraceStamp.serializer(), line.text()) }.getOrNull()
}
