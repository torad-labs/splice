// NEW: V4-160 — the team's day-scoped reads, moved verbatim out of TeamsRoutes.kt (concentration,
// 2026-09-18). TeamsRoutes.kt's header lists the routes these answer.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.WallClock
import splice.http.JsonReply
import splice.sessions.activity.ActivityStores
import splice.sessions.registry.SessionSource
import splice.sessions.teams.Team
import splice.sessions.teams.TeamStore
import splice.sessions.transcript.SentTexts
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

internal const val TEAM_ID = "team_id"
internal const val PACKET_NOTE = "no wire source: a SendMessage call carries no dispatch unit"
internal const val ACTIVITY_SAMPLE_NOTE = "labels are samples: one per activity side query the client sends, " +
    "about every 30 seconds while a session works; a gap is a session that sent none"

// why: milliseconds in a day. The reads are day-scoped because the activity store writes one
// file per day; this converts a day index to the epoch-millis window that file covers.
private const val DAY_MS = 86_400_000L

/** A sender's SendMessage texts by tool_use id, through the feature-owned transcript port. */
public fun interface SentTextSource {
    public fun read(session: String, head: String?, ids: Set<String>): SentTexts
}

/** A day-scoped panel's body, given the team, the stores and the day's start. */
internal fun interface DayPanel {
    fun body(team: Team, stores: ActivityStores, dayStart: Long): JsonObject
}

/** The team reads over the activity stores. A MEMBER is every session a slot ever held, as in the
 *  economics. CHAT TEXT is read on demand from the SENDER's transcript through [SentTextSource];
 *  the edge store never holds it, and a message whose text was not found carries text null and a
 *  missing_reason naming the path read, never an empty string. `packet` has no wire source, so every
 *  message carries null and the payload says why (6.1: the honest empty, never an invented value).
 *  Days are UTC calendar dates, today when the query omits one. Unwired stores answer 503. */
public class TeamReads internal constructor(
    private val teams: TeamSource,
    private val registry: SessionSource?,
    private val activity: ActivitySource,
    private val texts: SentTextSource,
    private val clock: WallClock,
) {
    public fun edges(id: String): JsonReply = daily(id, null) { team, stores, _ ->
        val members = Members(team, registry?.read().orEmpty())
        buildJsonObject {
            put(TEAM_ID, team.id)
            put("edges", buildJsonArray { members.edges(stores.edges.edges()).forEach { add(it.json()) } })
        }
    }

    public fun chat(id: String, day: String?): JsonReply = daily(id, day) { team, stores, start ->
        val members = Members(team, registry?.read().orEmpty())
        val today = members.edges(stores.edges.edges()).filter { it.edge.at in start until start + DAY_MS }
        val found = today.groupBy { it.edge.from }.mapValues { (from, sent) ->
            texts.read(from, members.headOf(from), sent.map { it.edge.id }.toSet())
        }
        buildJsonObject {
            put(TEAM_ID, team.id)
            put("day_start_epoch_millis", start)
            put("packet_note", PACKET_NOTE)
            put("messages", buildJsonArray { today.forEach { add(it.message(found[it.edge.from])) } })
        }
    }

    public fun activity(id: String, day: String?): JsonReply = daily(id, day) { team, stores, start ->
        val slots = Members(team, emptyList()).slotOfSession
        val rows = slots.keys.flatMap { stores.activity.rows(it) }.filter { it.at in start until start + DAY_MS }
        buildJsonObject {
            put(TEAM_ID, team.id)
            put("day_start_epoch_millis", start)
            put("sample_interval_note", ACTIVITY_SAMPLE_NOTE)
            put("upstream_label_queries", rows.count { it.upstream })
            put(
                "entries",
                buildJsonArray {
                    rows.filter { it.label != null }.sortedBy { it.at }.forEach { row ->
                        val label = row.label.orEmpty()
                        add(
                            buildJsonObject {
                                put("at", row.at)
                                put("session", row.session)
                                put("slot", slots[row.session]?.id)
                                put("head", row.head)
                                put("label", label.substringBefore('\n'))
                                put("detail", label.substringAfter('\n', "").trim().takeIf { it.isNotEmpty() })
                            },
                        )
                    }
                },
            )
        }
    }

    /** One team read: the store, the team and the activity stores, each a named refusal when absent.
     *  [day] is a UTC date, today when null; anything else is a 400. */
    private fun daily(id: String, day: String?, panel: DayPanel): JsonReply {
        val today = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()
        val date = if (day == null) today else parseDay(day)
        val store = teams()
        val team = store?.team(id)
        val stores = activity()
        val body = date?.let { d ->
            val start = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            team?.let { t -> stores?.let { panel.body(t, it, start) } }
        }
        return body?.let { JsonReply(HttpStatusCode.OK, it.toString()) } ?: refusal(id, day, store)
    }

    /** Why [daily] had nothing to answer, first cause first. */
    private fun refusal(id: String, day: String?, store: TeamStore?): JsonReply {
        val (status, message) = when {
            day != null && parseDay(day) == null -> HttpStatusCode.BadRequest to "day is a UTC date, YYYY-MM-DD: $day"
            store == null -> HttpStatusCode.ServiceUnavailable to TEAMS_UNWIRED
            store.team(id) == null -> HttpStatusCode.NotFound to "$NO_SUCH_TEAM$id"
            else -> HttpStatusCode.ServiceUnavailable to EDGES_UNWIRED
        }
        return JsonReply(status, buildJsonObject { put("error", message) }.toString())
    }

    /** Null for anything that is not a calendar date: the caller answers it with a 400 naming it.
     *  DateTimeParseException is not one of the kinds runCatchingCancellable folds, so it is caught here. */
    private fun parseDay(day: String): LocalDate? =
        try {
            LocalDate.parse(day)
        } catch (_: DateTimeParseException) {
            null
        }
}
