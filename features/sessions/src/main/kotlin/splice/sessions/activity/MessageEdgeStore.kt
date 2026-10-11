// NEW: V4-130, FEATURES.md 6 — the message edges observed on the wire: that a session sent a
// SendMessage, to which address, when. NO TEXT, ever: the message itself stays in the transcripts and
// is read from there on demand (FEATURES.md 4.13). Kept by default, as metadata, in `edges-<day>.jsonl`
// through ActivityDays.
//
// ONLY THE SENDER IS OBSERVED. A received edge is the same edge seen from the other end: the reader
// derives direction `in` by matching an edge's stored session, or its `to` address, against the asked
// session, so one observation serves both sessions and the two ends can never disagree.
//
// THE TOOL-USE ID RIDES IN THE ROW so reads de-duplicate by it. The observer's in-memory de-dupe
// (MessageEdges) cannot survive a daemon restart, and a restart is exactly when a retried request can
// carry a call the store already holds; the id makes that second row harmless rather than a second
// edge.
//
// A NAME IS RESOLVED WHEN THE EDGE IS STORED (V4-252). A SendMessage `to` is an address (`uds:<socket>`)
// or a session's name, and a name moves: a later session can take it. A reader that resolved a stored
// name through the live registry filed the call under whoever held the name at read time, so a fresh
// team's board showed a rehearsal's message to its 'gpt'. The row carries `to_session`, the one live
// session that held the name when it was stored. A name with no unique live holder stores an explicit
// null; a name row with no to_session key predates that marker and Teams may recover it from one named
// member's registry record. An address still reads by its address, regardless of the marker.
package splice.sessions.activity

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.memory.HeapReservations
import splice.core.storage.ActivityDays
import splice.core.storage.DayFiles
import splice.core.storage.DayInventory
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource

/** The day-file prefix edges are written under. */
internal const val EDGES_PREFIX: String = "edges"

/** One observed SendMessage. [from] is the sending session id, [to] the address or name the call
 *  named, [at] epoch ms of the observation, [id] the tool_use id that makes the row unique, and
 *  [toSession] the session that held the name [to] when the edge was stored, null for an address. */
public sealed class RecipientResolution {
    public data object Legacy : RecipientResolution()
    public data object NoHolder : RecipientResolution()
    public data class Held(val session: String) : RecipientResolution()
}

public data class MessageEdge(
    val from: String,
    val to: String,
    val at: Long,
    val id: String,
    val toSession: String? = null,
    val recipient: RecipientResolution = toSession?.let(RecipientResolution::Held) ?: RecipientResolution.Legacy,
) {
    init {
        require((recipient as? RecipientResolution.Held)?.session == toSession) {
            "recipient resolution must agree with the stored session"
        }
    }
}

/** The session a SendMessage name reaches as its edge is stored, read from [sessions]. */
public class NameHolders(private val sessions: SessionSource) {
    /** The one live registration holding [name]; null when none does, or more than one. A GONE
     *  registration holds nothing. */
    public fun sessionOf(name: String): String? = sessionOf(
        name,
        sessions.read().filter { it.availability != SessionAvailability.GONE },
    )

    /** V4-411: the one live registration whose socket is [address] (`uds:<socket>`); null when none is,
     *  or more than one. A GONE registration holds nothing, as it holds no name. */
    public fun sessionAt(address: String): String? = sessions.read()
        .filter { it.availability != SessionAvailability.GONE && it.address == address }
        .mapNotNull { it.sessionId }
        .distinct()
        .singleOrNull()

    /** A displayed [ref] is not a session ID prefix. Strip it, then require one named holder. */
    public fun sessionOf(name: String, records: List<SessionRecord>): String? =
        records.filter { it.name == bare(name) }.mapNotNull { it.sessionId }.distinct().singleOrNull()

    /** [name] without the `[ref]` a display appends to it. */
    public fun bare(name: String): String =
        Regex("^(.+) \\[[^\\[\\]]+\\]$").matchEntire(name)?.groupValues?.get(1) ?: name
}

/** Which edges a cut spares, asked once per dated row. Marlin, Oct 10, 2026: an active team keeps
 *  its edges, because its board is the record of a conversation still happening. */
public fun interface SparedEdges {
    public fun spares(edge: MessageEdge): Boolean
}

public class MessageEdgeStore(
    private val days: ActivityDays,
    private val files: DayFiles,
    private val retentionDays: Int,
    public val storing: Boolean,
    private val decode: MessageEdgeDecode = MessageEdgeCodec(),
    heap: HeapReservations? = null,
    maxCacheBytes: Long = EDGE_CACHE_BYTES,
) {
    private val cache = MessageEdgeCache(days, files, decode, heap, maxCacheBytes)
    private val counts = MessageEdgeTotals(days, files, decode, heap, maxCacheBytes)
    private val scans = MessageEdgeScan(days, files, decode, heap, maxCacheBytes)

    public fun inventory(): DayInventory = files.inventory(retentionDays)
    public fun deleteKept(): DayInventory = files.deleteKept(retentionDays)

    /**
     * Drop every edge observed before [momentMs], and say how many went. The save on Settings >
     * Your data reaches these too (Marlin, Oct 10, 2026): when a person says yes to a deletion,
     * everything the history covers is gone at that moment, and an edge is a record of who they
     * messaged. [spared] keeps the ones an active team still needs.
     *
     * A row this store cannot decode is KEPT, as the records side keeps an undated row: its moment
     * is unknown, so it cannot be shown to be one of the rows the person was counted and asked
     * about. Call from an I/O dispatcher; throws when the cut could not be made, so a caller never
     * reports a deletion that did not happen.
     */
    public fun trimBefore(momentMs: Long, spared: SparedEdges = SparedEdges { false }): Int {
        // The cache keys each day by its inode and mtime, so the atomic move the cut makes is a new
        // identity it drops and re-reads on the next call: there is nothing to invalidate by hand.
        return days.cut().keepOnly { line ->
            val edge = decode.parse(line)
            edge == null || edge.at >= momentMs || spared.spares(edge)
        }.lines.toInt()
    }

    /** The bytes [trimBefore] would remove with the same arguments, read and not changed, so a count shown before
     *  a cut is the figure the cut then frees. Throws when the days could not be read. */
    public fun bytesBefore(momentMs: Long, spared: SparedEdges = SparedEdges { false }): Long =
        days.lines().sumOf { line ->
            val edge = decode.parse(line)
            val kept = edge == null || edge.at >= momentMs || spared.spares(edge)
            if (kept) 0L else line.toByteArray().size + 1L
        }

    public fun deleted(): Boolean = files.deleted()

    public fun record(edge: MessageEdge) {
        if (!storing) return
        days.append(
            buildJsonObject {
                put("from", edge.from)
                put("to", edge.to)
                put("at", edge.at)
                put("id", edge.id)
                when (val recipient = edge.recipient) {
                    RecipientResolution.Legacy -> Unit
                    RecipientResolution.NoHolder -> put(TO_SESSION_KEY, JsonNull)
                    is RecipientResolution.Held -> put(TO_SESSION_KEY, recipient.session)
                }
            }.toString(),
        )
    }

    /** Every retained edge, oldest first, one per tool_use id (the earliest observation wins). With [since], only the days written at or after that instant are read and kept. */
    public fun edges(since: Long? = null): List<MessageEdge> = cache.edges(since)

    /** The retained edges [wanted] accepts, oldest first, read from the day files and not kept: for a caller that names
     *  the edges it needs (a team, a session) across the whole window, which the row cache cannot hold on a long one. */
    internal fun scan(wanted: EdgeWanted, since: Long? = null): List<MessageEdge> = scans.scan(wanted, since)

    /** What each session sent and was sent, over every retained day, as one snapshot. Holds a count per distinct
     *  sender and recipient, never the edges, so it does not grow with the window (MessageEdgeTotals). */
    public fun totals(): EdgeTotals = counts.totals()
}
