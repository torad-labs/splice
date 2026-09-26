// NEW: V4-160 — a team's members and the edges between them, moved verbatim out of TeamsRoutes.kt
// (concentration, 2026-09-18).
package splice.sessions.http

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.sessions.activity.MessageEdge
import splice.sessions.registry.SessionRecord
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.transcript.SentTexts

private const val DIRECTION_INTERNAL = "internal"
private const val DIRECTION_OUT = "out"
private const val DIRECTION_IN = "in"

/** What ends an address's scheme (`uds:`): a SendMessage `to` without one is a bare name. */
private const val ADDRESS_SCHEME_END = ':'

/** One team edge: the stored edge as [Addresses.reported], its direction, and the slots at each end. */
internal class TeamEdge(
    val edge: MessageEdge,
    val direction: String,
    val fromSlot: TeamSlot?,
    val toSlot: TeamSlot?,
    val fromHead: String?,
) {
    fun json(): JsonObject = buildJsonObject {
        put("from", edge.from)
        put("to", edge.to)
        put("at", edge.at)
        put("direction", direction)
        put("from_slot", fromSlot?.id)
        put("to_slot", toSlot?.id)
    }

    /** The chat line, with the text [sent] found for this call or the reason it has none. */
    fun message(sent: SentTexts?): JsonObject = buildJsonObject {
        put("at", edge.at)
        put("from", edge.from)
        put("from_slot", fromSlot?.id)
        put("from_head", fromHead)
        put("to", edge.to)
        put("to_slot", toSlot?.id)
        put("packet", JsonNull)
        HandedText.put(this, edge.id, sent)
    }
}

/** A team's members: every session its slots ever held, and the addresses the registry knows for
 *  them, so an edge's recipient (the session stored with a name, or an address) is matched to a slot. */
internal class Members(team: Team, private val records: List<SessionRecord>) {
    val slotOfSession: Map<String, TeamSlot> = team.slots
        .flatMap { slot -> (slot.sessionsHistory + listOfNotNull(slot.session)).map { it to slot } }
        .distinctBy { it.first }
        .toMap()
    private val addresses = Addresses(records)
    private val slotOfAddress: Map<String, TeamSlot> = records
        .mapNotNull { record ->
            val slot = record.sessionId?.let(slotOfSession::get)
            record.address?.let { address -> slot?.let { address to it } }
        }
        .toMap()

    /** The session's head: the registry's word, else its slot's. */
    fun headOf(session: String): String? =
        records.firstOrNull { it.sessionId == session }?.head ?: slotOfSession[session]?.head

    /** The edges touching a member, oldest first, each as [Addresses.reported]. An edge between two
     *  non-members is not the team's. A name reached the session that held it when the edge was stored,
     *  never whoever holds it now (V4-252): a board read after the name moved still files the call where
     *  it went. A name no session held then is a call to no session: a member's subagent is addressed by
     *  a bare name ("code-review") and answers its parent as "main", and that traffic is the member's own
     *  tool work, never a hand-off (V4-263). An address carries its scheme (`uds:`, SessionRegistry.kt's
     *  `address`) and reaches the member the registry holds it for. */
    fun edges(all: List<MessageEdge>): List<TeamEdge> = all.mapNotNull { stored ->
        val edge = addresses.reported(stored)
        val from = slotOfSession[edge.from]
        val held = edge.toSession
        val to = if (held != null) slotOfSession[held] else slotOfAddress[edge.to] ?: slotOfSession[edge.to]
        if (to == null && reachedNoSession(edge)) return@mapNotNull null
        direction(from, to)?.let { TeamEdge(edge, it, from, to, headOf(edge.from)) }
    }.sortedBy { it.edge.at }

    /** A call to a name no session held when it was stored: an address carries its scheme. */
    private fun reachedNoSession(edge: MessageEdge): Boolean =
        edge.toSession == null && ADDRESS_SCHEME_END !in edge.to

    private fun direction(from: TeamSlot?, to: TeamSlot?): String? = when {
        from != null && to != null -> DIRECTION_INTERNAL
        from != null -> DIRECTION_OUT
        to != null -> DIRECTION_IN
        else -> null
    }
}
