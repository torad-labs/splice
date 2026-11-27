package splice.app.mods

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.app.mods.events.ModEventText
import splice.app.mods.events.ModSecretSources
import splice.app.mods.events.ModTurns
import splice.app.mods.sessions.ModLaunches
import splice.app.mods.sessions.ModSessionLedger
import splice.app.mods.sessions.ModSessionTracker
import splice.core.turn.ObservedTurnEnd
import splice.core.turn.ObservedUsage
import splice.core.util.LogSink
import splice.head.observe.ObservedTurnFacts
import splice.launch.LaunchModSettings
import splice.launch.LaunchNotice
import java.nio.file.Path
import java.util.UUID

internal class ModEventsTestRig(
    root: Path,
    env: Map<String, String> = System.getenv(),
    overrideText: ModEventText? = null,
) {
    val runner = RunnerTestRig(root, env)
    val text = overrideText ?: ModEventText(ModSecretSources(runner.paths.envReader, env.keys))
    val sessions = ModSessionTracker(
        runner.runner.events,
        ModSessionLedger(runner.paths.modsDir, runner.paths.stateDir, LogSink {}),
        text,
    )
    val launches = ModLaunches(runner.paths.modsDir, LogSink {}, runner.mods, sessionEvents = sessions)
    private val turns = ModTurns(runner.runner.events, launches, sessions, text)

    suspend fun install(client: HttpClient, name: String, filter: String = "", base: String = "") {
        runner.install(
            client,
            runner.fixture(
                name,
                """
            export function register(on) {
              const note = (name) => async ($, e) => { $.log("event=" + JSON.stringify({name, payload:e})); };
              on("mod.start", async ($) => { $.log("env=" + JSON.stringify(Bun.env)); });
              for (const name of ["session.start", "session.end", "turn.start", "request.send", "response.receive",
                                  "stream.event"]) on(name, note(name));
              on("turn.complete", async ($, e) => {
                try { $.log("rows=" + JSON.stringify(await $.sessions.list())); } catch {}
                await note("turn.complete")($, e);
              });
              on("tool.call", $filter note("tool.call"));
            }
                """.trimIndent(),
            ),
            base = base,
        )
        runner.eventually { runner.log(name).takeIf { "env=" in it } }
    }

    fun launch(pid: Long? = null, settings: LaunchModSettings = LaunchModSettings(), cwd: String = "/work"): String =
        UUID.randomUUID().toString().also { launches.launched(LaunchNotice(it, pid, cwd, settings)) }

    fun begin(session: String, owner: String?, model: String = "asked") =
        begin(ObservedTurnFacts(session, owner, "head", model, false, null, 1))

    fun begin(facts: ObservedTurnFacts) = turns.begin(facts)

    fun complete(session: String, owner: String?) {
        begin(session, owner)?.completed(ObservedTurnEnd("ok", ObservedUsage(0, 0, 0, 0), 1, 2))
    }

    fun rows(mod: String): List<JsonObject> = runner.log(mod).lineSequence().last { it.startsWith("rows=") }
        .substringAfter("rows=").let { Json.parseToJsonElement(it).jsonArray.map { row -> row.jsonObject } }

    suspend fun through(mod: String, end: String, session: String? = null): List<RecordedModEvent> = runner.eventually {
        val events = runner.log(mod).lineSequence().filter { it.startsWith("event=") }
            .map { Json.parseToJsonElement(it.substringAfter("event=")).jsonObject }
            .map { RecordedModEvent(it.getValue("name").jsonPrimitive.content, it.getValue("payload").jsonObject) }
            .toList()
        events.takeIf { it.any { event -> event.name == end && (session == null || event.session == session) } }
    }
}

internal data class RecordedModEvent(val name: String, val payload: JsonObject) {
    val session: String get() = payload.getValue("session").jsonPrimitive.content
}
