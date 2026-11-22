// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). A Claude Desktop session running on agy:
// what each line Desktop writes does on agy, and what each message agy writes shows in Desktop.
//   - Desktop's `initialize` is answered with agy's commands and models; `interrupt` cancels agy's turn; `set_model`,
//     an effort (`apply_flag_settings`) and `set_permission_mode` set agy's options and mode; `mcp_status` says there
//     are no MCP servers; every other request is answered with an error, so Desktop never waits.
//   - A `user` line is said back as replayed, written to the transcript and sent as `session/prompt`, also while a
//     turn runs (agy steers it). A turn ends with its `result` once agy has answered every prompt sent in it.
//   - agy's updates are drawn by AgyTurn and its permission requests asked by AgyQuestions.
//   - A turn agy can't run (signed out, ended, refused) says why in the conversation and ends as an error.
package splice.provider.antigravity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import java.util.UUID

/** The parts of a Desktop session that outlive its turns. */
internal data class DesktopParts(
    val frames: DesktopFrames,
    val out: DesktopOut,
    val transcript: ClaudeTranscript,
    val record: ClaudeSessionRecord,
    val look: SessionLook,
)

/** Desktop session [parts] on agy [session], or on none while agy is unusable for [unusable]; [clock] times turns. */
internal class DesktopSession(
    private val parts: DesktopParts,
    private var session: AgySession?,
    private var unusable: String?,
    private val clock: WallClock,
) {
    private val turn = AgyTurn(parts.frames, parts.out, parts.transcript)
    private val questions = AgyQuestions(parts.frames, parts.out, turn)
    private val lists = DesktopLists()
    private val prompts = HashSet<Long>()
    private var look = parts.look
    private var startedAt = 0L
    private var usage: JsonObject = lists.usage(null)
    private var stop: String? = null
    private var window = AGY_WINDOW

    init {
        turn.draw.model = look.model
    }

    /** agy's messages, or null while there is no agy to hear from. */
    val fromAgy get() = session?.incoming

    /** One line Desktop wrote. */
    suspend fun desktop(line: String) {
        val message = JsonScalars.objectOrNull(Json, line) ?: return
        when (JsonScalars.str(message, "type")) {
            "control_request" -> parts.out(
                control(
                    JsonScalars.str(message, "request_id").orEmpty(),
                    message["request"] as? JsonObject ?: JsonObject(emptyMap()),
                ),
            )
            "control_response" -> questions.answered(session, message["response"] as? JsonObject)
            "user" -> user(message)
        }
    }

    /** One message agy wrote. */
    fun agy(message: JsonObject) {
        val method = JsonScalars.str(message, "method")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val id = message["id"]
        val agy = session
        when {
            method == "session/update" -> (params["update"] as? JsonObject)?.let(::update)
            agy == null || id == null -> Unit
            method == "session/request_permission" -> questions.ask(agy, id, params, look.permissionMode == BYPASS_MODE)
            method != null -> agy.refuse(id, method)
            JsonScalars.long(message, "id")?.let(prompts::remove) == true -> answeredPrompt(message)
        }
    }

    /** agy's output ended for [reason]: the turn running says so, and every later turn does too. */
    fun agyEnded(reason: String) {
        session = null
        unusable = reason
        questions.dropped(null)
        if (prompts.isEmpty()) return
        prompts.clear()
        turn.say(reason)
        finish(reason)
    }

    private suspend fun control(id: String, request: JsonObject): JsonObject {
        val subtype = JsonScalars.str(request, "subtype")
        return when (subtype) {
            "initialize" -> parts.frames.initialized(id, look.commands, lists.models(session?.catalog))
            "interrupt" -> interrupted(id)
            "set_model", "set_permission_mode", "apply_flag_settings" ->
                changed(request)?.let { parts.frames.controlError(id, it) } ?: parts.frames.controlOk(id)
            "mcp_status" -> parts.frames.controlOk(id, NO_MCP_SERVERS)
            else -> parts.frames.controlError(id, "$subtype is not offered by a session on agy")
        }
    }

    /** A change of model, permission mode or effort Desktop asked for in [request]; null once made, else why not. */
    private suspend fun changed(request: JsonObject): String? {
        val model = JsonScalars.str(request, "model")
        val mode = JsonScalars.str(request, "mode")
        val effort = JsonScalars.str(request["settings"] as? JsonObject, "effortLevel")
        return when {
            model != null -> model(model)
            mode != null -> mode(mode)
            else -> effort?.let { session?.effort(it) }
        }
    }

    private suspend fun model(model: String): String? {
        val refusal = session?.model(model)
        if (refusal == null) {
            look = look.copy(model = model)
            turn.draw.model = model
        }
        return refusal
    }

    /** Desktop's permission [mode]: agy's own mode, or, for `bypassPermissions`, every request allowed here. A
     *  session agy runs without prompts (it began in `bypassPermissions`) can't be given them back. */
    private suspend fun mode(mode: String): String? {
        val started = parts.look.permissionMode
        val refusal = if (started == BYPASS_MODE && mode != BYPASS_MODE) STARTED_BYPASSING else session?.mode(mode)
        if (refusal == null) look = look.copy(permissionMode = mode)
        return refusal
    }

    private fun interrupted(id: String): JsonObject {
        if (prompts.isNotEmpty()) {
            session?.cancel()
            questions.dropped(session)
        }
        return parts.frames.controlOk(id)
    }

    private fun user(line: JsonObject) {
        val uuid = JsonScalars.str(line, "uuid") ?: UUID.randomUUID().toString()
        val content = (line["message"] as? JsonObject)?.get("content") ?: JsonPrimitive("")
        parts.out(parts.frames.replay(line, uuid))
        parts.transcript.user(uuid, content)
        if (prompts.isEmpty()) begin()
        val prompt = session?.prompt(content)
        if (prompt != null) {
            prompts += prompt
            return
        }
        if (prompts.isNotEmpty()) return
        val reason = unusable ?: AGY_ENDED
        turn.say(reason)
        finish(reason)
    }

    private fun begin() {
        startedAt = clock()
        usage = lists.usage(null)
        stop = null
        parts.out(parts.frames.init(look))
        parts.record.write(busy = true)
    }

    private fun answeredPrompt(message: JsonObject) {
        val error = message["error"] as? JsonObject
        val result = message["result"] as? JsonObject
        stop = JsonScalars.str(result, "stopReason") ?: stop
        (result?.get("usage") as? JsonObject)?.let { usage = lists.usage(it) }
        val failure = error?.let {
            if (JsonScalars.int(it, "code") == AUTH_REQUIRED) AGY_SIGN_IN else "agy: ${JsonScalars.str(it, "message")}"
        }
        if (failure != null) turn.say(failure)
        if (prompts.isEmpty()) finish(failure)
    }

    /** The turn's `result`, an error when [error] says what went wrong. A cancelled turn's last message has no stop. */
    private fun finish(error: String?) {
        turn.end(stop.takeUnless { it == STOP_CANCELLED || it == STOP_MAX_TURN_REQUESTS })
        parts.out(parts.frames.result(TurnEnd(stop, error, clock() - startedAt, usage, look.model, window)))
        parts.record.write(busy = false)
    }

    private fun update(update: JsonObject) {
        when (JsonScalars.str(update, "sessionUpdate")) {
            "available_commands_update" -> look = look.copy(commands = lists.commands(update))
            "usage_update" -> {
                JsonScalars.long(update, "used")?.let { turn.draw.used = it }
                JsonScalars.long(update, "size")?.let { window = it }
            }
            else -> {
                session?.heard(update)
                if (prompts.isNotEmpty()) turn.update(update)
            }
        }
    }
}

/** `mcp_status`'s answer: a session on agy runs none of Desktop's MCP servers. */
private val NO_MCP_SERVERS = buildJsonObject { put("mcpServers", JsonArray(emptyList())) }

private const val STOP_CANCELLED = "cancelled"
private const val STOP_MAX_TURN_REQUESTS = "max_turn_requests"
private const val AGY_ENDED = "agy has ended"
private const val STARTED_BYPASSING =
    "this session's agy was started without permission prompts; start a new session to have them"
