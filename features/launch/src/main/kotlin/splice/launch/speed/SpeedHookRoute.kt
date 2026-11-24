// NEW: gpt-speed (docs/specs/gpt-speed.md, decisions 2 and 3) — `POST /hooks/speed/{head}`, the receiving end of the
// UserPromptSubmit hook a head signed in with ChatGPT carries (SpeedHook). The body is the hook JSON Claude Code pipes to
// the script: `session_id` and `prompt`. A /fast or /ultrafast sets that session's speed (SessionSpeeds) and is
// answered with a decision that stops the prompt, its reason the reply the person reads; any other prompt is answered
// `{}` and goes on to the model untouched.
//
// AS CODEX DOES (codex-rs tui chatwidget/service_tiers.rs, rust-v0.169.0): bare, a command turns its tier on, or back to
// standard when that tier is already on; `on` and `off` set it whatever it was, and `off` always means standard. Fast
// and Ultrafast replace each other. A tier the session's model does not offer is refused, naming the model and the
// tiers it does offer, and nothing changes. The session's model is its last turn's, or the head's pinned model before
// one.
//
// ALWAYS 200: a hook that errors would let the command through to the model as words. A refusal is a decision too.
package splice.launch.speed

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.speed.SpeedCommand
import splice.client.speed.SpeedCommands
import splice.core.speed.Speed
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.launch.LaunchHeads
import splice.launch.recipe.LaunchSpeeds

/** The same shape the resume hook admits as a session id. */
private val SESSION_ID_SHAPE = Regex("[A-Za-z0-9_-]{1,128}")

// why: the reply to a command that puts a fast session back at standard speed.
private const val BACK_TO_STANDARD = "This session is back at standard speed."

public class SpeedHookRoute(
    private val heads: LaunchHeads,
    private val speeds: LaunchSpeeds,
    private val log: LogSink,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val doorResolve = DoorSpeedResolve(heads, speeds)

    public suspend fun speed(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val body = call.receiveText()
        val answer = Cancellables.runCatchingCancellable { decide(key, body) }
            .getOrElse { blocked("splice could not switch this session's speed, so it is unchanged.") }
        call.respondText(JsonWire.string(answer), ContentType.Application.Json)
    }

    /** The hook's answer to [body] on head [key]: `{}` for a prompt that is not a speed command, else a decision that
     *  stops it. Internal so the decision is tested without a socket. */
    internal fun decide(key: String, body: String): JsonObject {
        val hook = Cancellables.runCatchingBestEffort { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val command = hook?.let { JsonScalars.str(it, "prompt") }?.let(SpeedCommands::parse)
            ?: return JsonObject(emptyMap())
        val session = JsonScalars.str(hook, "session_id").orEmpty()
        val head = heads.byKey(key)?.spec
        val unchanged = "so nothing changed."
        return when {
            !SESSION_ID_SHAPE.matches(session) -> blocked("${command.name} found no session to set, $unchanged")
            head != null -> {
                val model = speeds.sessions.lastModel(session) ?: head.models.pinnedModel
                blocked(switched(key, session, model, command))
            }
            doorResolve.isDoor(key) -> decideDoor(session, command, unchanged)
            else -> blocked("${command.name} works on a head splice serves; this one is not, $unchanged")
        }
    }

    private fun decideDoor(session: String, command: SpeedCommand, unchanged: String): JsonObject =
        when (val resolution = doorResolve.match(session, command, unchanged)) {
            is DoorSpeedMatch.Matched -> blocked(switched(resolution.catalogKey, session, resolution.model, command))
            DoorSpeedMatch.PassThrough -> JsonObject(emptyMap())
            is DoorSpeedMatch.Refused -> blocked(resolution.reason)
        }

    /** What [command] does to [session], whose model is [model], said as the person reads it. */
    private fun switched(key: String, session: String, model: String, command: SpeedCommand): String {
        val now = speeds.sessions.speedOf(session)
        val wanted = wanted(command, now)
        val tier = wanted?.takeUnless { it == Speed.STANDARD }?.let { speeds.catalog.tierFor(key, model, it) }
        return when {
            wanted == null -> {
                "${command.name} takes on, off or nothing: bare turns ${command.speed.title} on, or back to " +
                    "standard when it is on; on sets it; off is standard speed. " +
                    "This session's speed is unchanged (${now.title})."
            }
            wanted == Speed.STANDARD -> {
                set(key, session, Speed.STANDARD)
                if (now == Speed.STANDARD) "This session runs at standard speed." else BACK_TO_STANDARD
            }
            tier == null -> refusal(key, model, wanted, now)
            else -> {
                set(key, session, wanted)
                "This session now runs at ${SpeedCommands.costOf(wanted, tier)} " +
                    "${command.name} again, or ${command.name} off, goes back to standard speed."
            }
        }
    }

    /** The speed [command] asks for when the session runs at [now], as Codex's commands toggle; null for an argument
     *  that is not on or off. */
    private fun wanted(command: SpeedCommand, now: Speed): Speed? = when (command.ask) {
        SpeedCommand.Ask.UNKNOWN -> null
        SpeedCommand.Ask.OFF -> Speed.STANDARD
        SpeedCommand.Ask.ON -> command.speed
        SpeedCommand.Ask.TOGGLE -> if (now == command.speed) Speed.STANDARD else command.speed
    }

    private fun refusal(key: String, model: String, wanted: Speed, now: Speed): String {
        val offered = speeds.catalog.tiersOf(key, model).mapNotNull { it.speed?.title }.distinct()
        val offers = if (offered.isEmpty()) "no speed but standard" else offered.joinToString(" and ")
        return "$model does not offer ${wanted.title}; it offers $offers. " +
            "This session's speed is unchanged (${now.title})."
    }

    private fun set(key: String, session: String, speed: Speed) {
        if (speeds.sessions.speedOf(session) == speed) return
        speeds.sessions.set(session, speed)
        log("[speed] ${LogSafe.str(key)}: session ${LogSafe.str(session)} now runs at ${LogSafe.str(speed.word)}\n")
    }

    private fun blocked(reason: String): JsonObject = buildJsonObject {
        put("decision", "block")
        put("reason", reason)
    }
}
