// NEW: V4-131 — the shared fixture for the team and project route tests: a three-session registry
// (lead and builder in one repo, an outsider elsewhere), a team store, the activity stores and heads
// whose perf rows and catalogs the economics read. Every value is fixed, so the payloads are asserted
// whole.
package console.v4131

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.client.ClaudePolicy
import splice.control.CompactView
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadTrees
import splice.control.HeadUsageSource
import splice.control.LaunchSpec
import splice.control.ManagedHead
import splice.control.PerfRow
import splice.control.PerfRowsSource
import splice.control.PerfRowsWindow
import splice.control.RateLimitView
import splice.control.UsageView
import splice.core.activity.ActivityStores
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.sessions.SessionRegistry
import splice.core.teams.Team
import splice.core.teams.TeamSlot
import splice.core.teams.TeamStore
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

/** 2026-09-18T10:00:00Z, inside the UTC day that starts at [DAY_START]. */
const val AT: Long = 1_789_725_600_000L
const val DAY_START: Long = 1_789_689_600_000L
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
            headOf = { pid -> if (pid == 1L) "claude" else "codex" },
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

    /** [field] of every object in [array] of [body], as text. */
    fun column(body: JsonObject, array: String, field: String): List<String> =
        body.getValue(array).jsonArray.map { it.jsonObject.getValue(field).jsonPrimitive.content }

    /** The object in [array] of [body] whose [field] is [value]. */
    fun find(body: JsonObject, array: String, field: String, value: String): JsonObject =
        body.getValue(array).jsonArray.map { it.jsonObject }
            .single { it.getValue(field).jsonPrimitive.content == value }

    /** A perf row as PerfStats writes it: the session TAG, never the whole id. */
    fun row(ts: Long, session: String?, input: Long, cached: Long = 0L, out: Long = 10L) = PerfRow(
        ts = ts,
        outcome = "ok",
        fields = mapOf("in_tokens" to input, "cached_tokens" to cached, "out_tokens" to out),
        model = "m",
        session = session?.take(8),
    )

    fun head(
        key: String,
        rows: List<PerfRow>,
        rates: ModelRates? = ModelRates(1.0, 0.1, 2.0),
        own: Path? = null,
    ) = ManagedHead(
        head = object : Head {
            override val key: String = key
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "test", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
        launchSpec = own?.let(::spec),
        perfRows = PerfRowsSource { since ->
            PerfRowsWindow(rows.filter { it.ts >= since }, oldestHeldTs = rows.minOfOrNull { it.ts })
        },
        catalog = ModelCatalog(
            discoveryPrefix = "claude-$key--",
            models = listOf(ModelEntry("m", "M", contextWindow = 1_000, rates = rates)),
            defaultContextWindow = 1_000,
        ),
    )

    private fun spec(own: Path) = LaunchSpec(
        trees = HeadTrees(own),
        pinnedModel = "m",
        availableModelIds = listOf("m"),
        modelLabels = mapOf("m" to "M"),
        contextWindow = 1_000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "",
        loginCommand = "",
        signInLabel = "",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 0,
        inferenceToken = "t",
        apiTimeoutMs = 1_000,
    )
}
