// NEW: V4-160 — a team's members and the edges between them, moved verbatim out of TeamsRoutes.kt
// (concentration, 2026-09-18).
package splice.control.api.sessions

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.transcript.SentTexts
import splice.core.activity.MessageEdge
import splice.core.sessions.SessionRecord
import splice.core.teams.Team
import splice.core.teams.TeamSlot

private const val DIRECTION_INTERNAL = "internal"
private const val DIRECTION_OUT = "out"
private const val DIRECTION_IN = "in"

/** One team edge: the stored edge with its `to` resolved, its direction, and the slots at each end. */
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
        val text = sent?.texts?.get(edge.id)
        put("text", text)
        put("text_source", sent?.path?.takeIf { text != null })
        put("missing_reason", if (text == null) missingReason(sent) else null)
    }

    private fun missingReason(sent: SentTexts?): String = when {
        sent == null -> "no transcript lookup ran"
        sent.path == null -> "no transcript for the sender in " + sent.searched.joinToString()
        else -> "the call is not in ${sent.path}"
    }
}

/** A team's members: every session its slots ever held, and the addresses the registry knows for
 *  them, so an edge's `to` (an address or a session name) is matched to a slot. */
internal class Members(team: Team, private val records: List<SessionRecord>) {
    val slotOfSession: Map<String, TeamSlot> = team.slots
        .flatMap { slot -> (slot.sessionsHistory + listOfNotNull(slot.session)).map { it to slot } }
        .distinctBy { it.first }
        .toMap()
    private val addressOfName: Map<String, String> = records
        .mapNotNull { record -> record.name?.let { name -> record.address?.let { name to it } } }
        .distinctBy { it.first }
        .toMap()
    private val slotOfAddress: Map<String, TeamSlot> = records
        .mapNotNull { record ->
            val slot = record.sessionId?.let(slotOfSession::get)
            record.address?.let { address -> slot?.let { address to it } }
        }
        .toMap()

    /** The session's head: the registry's word, else its slot's. */
    fun headOf(session: String): String? =
        records.firstOrNull { it.sessionId == session }?.head ?: slotOfSession[session]?.head

    /** The edges touching a member, oldest first, `to` resolved to an address where the registry knows
     *  the name. An edge between two non-members is not the team's. */
    fun edges(all: List<MessageEdge>): List<TeamEdge> = all.mapNotNull { stored ->
        val edge = stored.copy(to = addressOfName[stored.to] ?: stored.to)
        val from = slotOfSession[edge.from]
        val to = slotOfAddress[edge.to] ?: slotOfSession[edge.to]
        direction(from, to)?.let { TeamEdge(edge, it, from, to, headOf(edge.from)) }
    }.sortedBy { it.edge.at }

    private fun direction(from: TeamSlot?, to: TeamSlot?): String? = when {
        from != null && to != null -> DIRECTION_INTERNAL
        from != null -> DIRECTION_OUT
        to != null -> DIRECTION_IN
        else -> null
    }
}
