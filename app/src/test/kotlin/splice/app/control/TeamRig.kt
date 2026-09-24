// NEW: V4-131 — the fixed registry, team, and activity fixture used by the control server's team and
// project route wiring test. Feature-owned projection fixtures moved with :features-sessions.
package splice.app.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import java.nio.file.Files
import java.nio.file.Path

/** 2026-09-18T10:00:00Z, inside the UTC day that starts at [DAY_START]. */
const val AT: Long = 1_789_725_600_000L
const val LEAD: String = "a1a1a1a1-0000-4000-8000-000000000001"
const val BUILDER: String = "b2b2b2b2-0000-4000-8000-000000000002"
const val OUTSIDER: String = "c3c3c3c3-0000-4000-8000-000000000003"

/** The builder's slot held this session before [BUILDER]; it is in the history, not the registry. */
const val OLD_BUILDER: String = "d4d4d4d4-0000-4000-8000-000000000004"

class TeamRig(val tmp: Path) {
    val repo: Path = Files.createDirectories(tmp.resolve("repo"))
    val store: TeamStore = TeamStore(tmp.resolve("state/teams.json"), WallClock { AT })
    val stores: ActivityStores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { AT })
    val registry: SessionRegistry

    init {
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        val elsewhere = Files.createDirectories(tmp.resolve("elsewhere"))
        val sessions = listOf(Triple(1, LEAD, repo), Triple(2, BUILDER, repo), Triple(3, OUTSIDER, elsewhere))
        for ((pid, id, cwd) in sessions) {
            val name = if (pid == 1) ""","name":"lead"""" else ""
            val row = """{"pid":$pid,"sessionId":"$id","cwd":"$cwd","updatedAt":${AT + pid},""" +
                """"messagingSocketPath":"/run/$pid.sock"$name}"""
            Files.writeString(dir.resolve("$pid.json"), row)
        }
        registry = SessionRegistry(
            sessionsDir = dir,
            routeOf = { pid -> SessionRoute.Head(if (pid == 1L) "claude" else "codex") },
            pidAlive = { true },
            clock = { AT },
        )
    }

    /** The team: a lead on claude and a builder on codex whose slot was rebound once. */
    fun team(): Team {
        val id = store.upsert(
            Team(
                name = "atlas",
                goal = "ship",
                repo = repo.toString(),
                slots = listOf(
                    TeamSlot(id = "lead", role = "orchestrator", head = "claude", lead = true),
                    TeamSlot(id = "b1", role = "builder", head = "codex"),
                ),
            ),
        ).id
        store.bind(id, mapOf("lead" to LEAD, "b1" to OLD_BUILDER))
        return store.bind(id, mapOf("b1" to BUILDER))
    }

    fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** The object in [array] of [body] whose [field] is [value]. */
    fun find(body: JsonObject, array: String, field: String, value: String): JsonObject =
        body.getValue(array).jsonArray.map { it.jsonObject }
            .single { it.getValue(field).jsonPrimitive.content == value }
}
