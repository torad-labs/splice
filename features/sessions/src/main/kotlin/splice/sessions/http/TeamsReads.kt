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
internal const val ACTIVITY_SAMPLE_NOTE = "labels are samples: at most one per session every 30 seconds, " +
    "from its latest tool call while it works or from its client's own activity query; a gap is a session " +
    "that called no tool"

// why: milliseconds in a day. The reads are day-scoped because the activity store writes one
// file per day; this converts a day index to the epoch-millis window that file covers.
private const val DAY_MS = 86_400_000L

// why: the longest local day, 25 hours on a DST fall-back: a caller's own window is one of its days.
private const val LONGEST_DAY_MS = 90_000_000L

/** A sender's SendMessage texts by tool_use id, through the feature-owned transcript port. */
public fun interface SentTextSource {
    public fun read(session: String, head: String?, ids: Set<String>): SentTexts
}

/** A day-scoped panel's body, given the team, the stores and the day's half-open window. */
internal fun interface DayPanel {
    fun body(team: Team, stores: ActivityStores, day: LongRange): JsonObject
}

/** The team reads over the activity stores. A MEMBER is every session a slot ever held, as in the
 *  economics. CHAT TEXT is read on demand from the SENDER's transcript through [SentTextSource];
 *  the edge store never holds it, and a message whose text was not found carries text null and a
 *  missing_reason naming the path read, never an empty string. `packet` has no wire source, so every
 *  message carries null and the payload says why (6.1: the honest empty, never an invented value).
 *  A day is the caller's own window when it sends one, `?from=&to=` in epoch millis (V4-249: the
 *  console sends its viewer's local day, so the board turns over at the viewer's midnight, not at
 *  00:00 UTC); else `?day=`, a UTC calendar date, today when the query omits it. Unwired stores
 *  answer 503. */
public class TeamReads internal constructor(
    private val teams: TeamSource,
    private val registry: SessionSource?,
    private val activity: ActivitySource,
    private val texts: SentTextSource,
    private val clock: WallClock,
) {
    public fun edges(id: String): JsonReply = daily(id, null, null, null) { team, stores, _ ->
        val members = Members(team, registry?.read().orEmpty())
        buildJsonObject {
            put(TEAM_ID, team.id)
            put("edges", buildJsonArray { members.edges(stores.edges.edges()).forEach { add(it.json()) } })
        }
    }

    public fun chat(id: String, day: String?, from: String? = null, to: String? = null): JsonReply =
        daily(id, day, from, to) { team, stores, window ->
            val members = Members(team, registry?.read().orEmpty())
            val today = members.edges(stores.edges.edges()).filter { it.edge.at in window }
            val found = today.groupBy { it.edge.from }.mapValues { (sender, sent) ->
                texts.read(sender, members.headOf(sender), sent.map { it.edge.id }.toSet())
            }
            buildJsonObject {
                put(TEAM_ID, team.id)
                put("day_start_epoch_millis", window.first)
                put("packet_note", PACKET_NOTE)
                put("messages", buildJsonArray { today.forEach { add(it.message(found[it.edge.from])) } })
            }
        }

    public fun activity(id: String, day: String?, from: String? = null, to: String? = null): JsonReply =
        daily(id, day, from, to) { team, stores, window ->
            val slots = Members(team, emptyList()).slotOfSession
            val rows = slots.keys.flatMap { stores.activity.rows(it) }.filter { it.at in window }
            buildJsonObject {
                put(TEAM_ID, team.id)
                put("day_start_epoch_millis", window.first)
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

    /** One team read: the store, the team and the activity stores, each a named refusal when absent,
     *  and a query that names no day ([window]) a 400. */
    private fun daily(id: String, day: String?, from: String?, to: String?, panel: DayPanel): JsonReply {
        val window = window(day, from, to)
        val store = teams()
        val team = store?.team(id)
        val stores = activity()
        val body = window?.let { w -> team?.let { t -> stores?.let { panel.body(t, it, w) } } }
        return body?.let { JsonReply(HttpStatusCode.OK, it.toString()) }
            ?: refusal(id, if (window == null) malformed(day, from, to) else null, store)
    }

    /** The half-open window a read covers: the caller's own when it sends a bound, else the UTC date
     *  [day]. Null for a query that names no day; [malformed] says why. */
    private fun window(day: String?, from: String?, to: String?): LongRange? =
        if (from == null && to == null) utcDay(day) else ownDay(day, from, to)

    /** The UTC date [day], today when null; null when it is not a calendar date. */
    private fun utcDay(day: String?): LongRange? {
        val today = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()
        val date = if (day == null) today else parseDay(day)
        val start = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: return null
        return start until start + DAY_MS
    }

    /** [from, to) when both are epoch millis, from not negative and before to, at most [LONGEST_DAY_MS]
     *  apart; null otherwise, and null beside a [day] too, since a range and a date could only disagree.
     *  A negative from is refused as PerfRoutes refuses a negative since, and it keeps the width from
     *  overflowing (V4-285: from=Long.MIN_VALUE, to=Long.MAX_VALUE wrapped to -1 and read every edge). */
    private fun ownDay(day: String?, from: String?, to: String?): LongRange? {
        val start = from?.toLongOrNull()?.takeIf { it >= 0 }
        val end = to?.toLongOrNull()
        val valid = day == null && start != null && end != null && end > start && end - start <= LONGEST_DAY_MS
        return if (valid) start until end else null
    }

    private fun malformed(day: String?, from: String?, to: String?): String =
        if (from == null && to == null) {
            "day is a UTC date, YYYY-MM-DD: $day"
        } else {
            "from and to are one day's bounds in epoch milliseconds, from not negative and before to and at most " +
                "25 hours apart, never beside day: from=$from to=$to day=$day"
        }

    /** Why [daily] had nothing to answer, first cause first. */
    private fun refusal(id: String, badQuery: String?, store: TeamStore?): JsonReply {
        val (status, message) = when {
            badQuery != null -> HttpStatusCode.BadRequest to badQuery
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
