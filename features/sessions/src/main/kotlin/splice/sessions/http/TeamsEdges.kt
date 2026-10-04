// NEW: V4-160 — a team's members and the edges between them, moved verbatim out of TeamsRoutes.kt
// (concentration, 2026-09-18).
package splice.sessions.http

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.sessions.activity.MessageEdge
import splice.sessions.activity.NameHolders
import splice.sessions.activity.RecipientResolution
import splice.sessions.registry.SessionRecord
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.transcript.SentTexts

private const val DIRECTION_INTERNAL = "internal"
private const val DIRECTION_OUT = "out"
private const val DIRECTION_IN = "in"

/** What ends an address's scheme (`uds:`): a SendMessage `to` without one is a bare name. */
private const val ADDRESS_SCHEME_END = ':'
private val SUBAGENT_NAMES = setOf("main", "code-review")

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
internal class Members(
    private val team: Team,
    private val records: List<SessionRecord>,
    private val names: NameHolders? = null,
) {
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
     *  never whoever holds it now (V4-252). Only a row written before to_session existed can resolve
     *  through one named registry member; an explicit null never does. Subagent names ("main",
     *  "code-review") remain tool traffic, not team hand-offs (V4-263), even if a member reused one.
     *  An address carries its scheme (`uds:`) and reaches the registry member holding it. */
    fun edges(all: List<MessageEdge>): List<TeamEdge> = all.mapNotNull { stored ->
        val edge = addresses.reported(stored)
        val from = slotOfSession[edge.from]
        val held = heldSession(edge)
        val to = if (held != null) {
            slotOfSession[held]
        } else {
            slotOfAddress[edge.to] ?: slotOfSession[edge.to] ?: slotSpelledBy(edge, from)
        }
        if (to == null && reachedNoSession(edge)) return@mapNotNull null
        direction(from, to)?.let { TeamEdge(edge, it, from, to, headOf(edge.from)) }
    }.sortedBy { it.edge.at }

    /** Only pre-resolution rows may recover one named team member; explicit null never does. */
    private fun heldSession(edge: MessageEdge): String? = when {
        edge.recipient != RecipientResolution.Legacy -> edge.toSession
        ADDRESS_SCHEME_END in edge.to || edge.to in SUBAGENT_NAMES -> null
        else -> names?.sessionOf(edge.to, records)
    }

    /** V4-402: a row written before to_session existed, read after the sessions are gone. The registry
     *  that could have named the holder emptied with them, and 24 of a walk's 34 hand-offs vanished. The
     *  name is read as the team's own slot it spells, for a sender that is a member of the team and only
     *  when no registry record carries the name: one holder outside the team, or two, is still that
     *  session's or nobody's (V4-391), and an explicit null never reaches here. */
    private fun slotSpelledBy(edge: MessageEdge, from: TeamSlot?): TeamSlot? {
        val holders = names ?: return null
        val name = holders.bare(edge.to)
        val open = from != null && edge.recipient == RecipientResolution.Legacy &&
            ADDRESS_SCHEME_END !in edge.to && edge.to !in SUBAGENT_NAMES
        return if (open && records.none { it.name == name }) team.slots.singleOrNull { it.id == name } else null
    }

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
