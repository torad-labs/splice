// PORT-OF: features/sessions/src/main/kotlin/splice/sessions/http/ProjectsRoutes.kt @ 9cde3b3e8 — invariants: UTC day, alive sessions, no local-step turns.
package splice.sessions.http

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.PerfKeys
import splice.sessions.query.SessionHead
import splice.sessions.query.SessionPerfWindow
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.teams.Team
import java.time.Instant
import java.time.ZoneOffset

// Perf rows carry the first eight characters of a session id, not its full id.
private const val PROJECT_PERF_TAG = 8

/** The same day's perf windows are shared by every project row in a request. */
internal class ProjectEconomics(private val heads: Map<String, SessionHead>, now: Long) {
    private val dayStart = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val today: List<Pair<SessionHead, SessionPerfWindow>> by lazy {
        heads.values.mapNotNull { head -> head.perfRows?.window(dayStart)?.let { head to it } }
    }

    fun row(sessions: List<SessionRecord>, teams: List<Team>): JsonObject {
        val held = teams.flatMap { team -> team.slots.flatMap { it.sessionsHistory } }
        val tags = (sessions.mapNotNull { it.sessionId } + held).map { it.take(PROJECT_PERF_TAG) }.toSet()
        val tally = PerfTally()
        for ((head, window) in today) {
            // EconomicsStore counts local code-mode steps separately from turns; a project does too.
            window.rows.filter { it.session in tags && it.fields[PerfKeys.LOCAL_STEP] != 1L }
                .forEach { tally.add(it, head.catalog) }
        }
        val touched = sessions.flatMap { listOfNotNull(it.updatedAt, it.statusUpdatedAt, it.startedAt) }
        val last = (touched + listOfNotNull(tally.lastAt)).maxOrNull()
        return buildJsonObject {
            // STALE means the registry has not refreshed, not that its pid exited: it is still running.
            put("live_sessions", sessions.count { it.availability != SessionAvailability.GONE })
            put("teams", teams.count { !it.archived })
            put("turns_today", tally.turns)
            put("cost_today_usd", tally.costUsd)
            put("unpriced_turns_today", tally.unpricedTurns)
            put("day_start", dayStart)
            put("last_activity", last?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
}
