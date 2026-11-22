// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). A Claude Desktop session running on agy:
// what each line Desktop writes does on agy, and what each message agy writes shows in Desktop.
package splice.provider.antigravity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import java.util.UUID

/** Builds [DesktopParts] once the session id is known (either from Desktop's flags or agy's `session/new`). */
internal fun interface PartsFactory {
    fun build(sessionId: String): DesktopParts
}

/** The parts of a Desktop session that outlive its turns. */
internal data class DesktopParts(
    val frames: DesktopFrames,
    val out: DesktopOut,
    val transcript: ClaudeTranscript,
    val record: ClaudeSessionRecord,
    val look: SessionLook,
)

/** Desktop session driven on agy via [coordinator] and [mcpState]; [clock] times turns. */
internal class DesktopSession(
    private val claude: ClaudeArgs,
    private val partsFactory: PartsFactory,
    private val coordinator: AgyMcpCoordinator,
    private val mcpState: McpState,
    private val bridge: McpBridge,
    private val clock: WallClock,
) {
    private val lists = DesktopLists()
    private val prompts = HashSet<Long>()
    private var parts: DesktopParts? = null
    private var turn: AgyTurn? = null
    private var questions: AgyQuestions? = null
    private var session: AgySession? = null
    private var unusable: String? = null
    private var opened = false
    private var commands: List<AgyCommand> = emptyList()
    private var look: SessionLook? = null
    private var startedAt = 0L
    private var usage: JsonObject = lists.usage(null)
    private var stop: String? = null
    private var window = AGY_WINDOW
    private var used = 0L
    private var pendingHandoff: String? = null

    /** True once Desktop asked to switch to a non-Antigravity model so seatd-pipe can hand off to Claude Code. */
    var switchingOut: Boolean = false
        private set

    /** True when the session should exit cleanly (after switching out and finishing any in-flight turn). */
    val shouldExit: Boolean get() = switchingOut && prompts.isEmpty()

    /** agy's messages, or null while there is no agy to hear from. */
    val fromAgy get() = session?.incoming

    /** Removes the session record file when the session ends. */
    fun closeRecord() {
        parts?.record?.remove()
    }

    /** One line Desktop wrote. */
    suspend fun desktop(line: String) {
        val message = JsonScalars.objectOrNull(Json, line) ?: return
        when (JsonScalars.str(message, "type")) {
            "control_request" -> {
                val reply = control(
                    JsonScalars.str(message, "request_id").orEmpty(),
                    message["request"] as? JsonObject ?: JsonObject(emptyMap()),
                )
                if (reply != null) checkNotNull(parts).out(reply)
            }
            "control_response" -> {
                val resp = message["response"] as? JsonObject
                if (!bridge.answered(resp)) questions?.answered(session, resp)
            }
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
            method == "session/request_permission" ->
                questions?.ask(agy, id, params, (look ?: parts?.look)?.permissionMode == BYPASS_MODE)
            method != null -> agy.refuse(id, method)
            JsonScalars.long(message, "id")?.let(prompts::remove) == true -> answeredPrompt(message)
        }
    }

    /** agy's output ended for [reason]: the turn running says so, and every later turn does too. */
    fun agyEnded(reason: String) {
        session = null
        unusable = reason
        questions?.dropped(null)
        if (prompts.isEmpty()) {
            turn?.closeOpenSubagents()
            return
        }
        prompts.clear()
        turn?.say(reason)
        finish(reason)
    }

    private suspend fun ensureOpen(desktopSpecs: List<AgyMcpSpec>): DesktopParts {
        val existing = parts
        if (opened && existing != null) return existing
        opened = true
        val res = coordinator.open(desktopSpecs, AgyMessageSink(::agy))
        session = res.session
        unusable = res.unusable
        val resolvedId = claude.sessionId ?: claude.resume ?: res.session?.id ?: UUID.randomUUID().toString()
        val built = partsFactory.build(resolvedId)
        val agyTurn = AgyTurn(built.frames, built.out, built.transcript, mcpState)
        agyTurn.draw.model = built.look.model
        agyTurn.draw.used = used
        parts = built
        turn = agyTurn
        questions = AgyQuestions(built.frames, built.out, agyTurn)
        look = built.look.copy(commands = commands, mcpServers = mcpState.initServers())
        built.record.write(busy = false)
        if (claude.resume != null) pendingHandoff = LiveHandoff().of(built.transcript.file, res.resumed)
        return built
    }

    private suspend fun control(id: String, request: JsonObject): JsonObject? {
        val subtype = JsonScalars.str(request, "subtype")
        return when (subtype) {
            "initialize" -> {
                val p = ensureOpen(coordinator.readInitializeSpecs(request))
                p.frames.initialized(id, commands, lists.models(session?.catalog), mcpState.applied)
            }
            "set_model" -> setModelControl(id, request)
            "mcp_status" -> ensureOpen(emptyList()).frames.controlOk(id, mcpState.statusResponse())
            "mcp_set_servers" -> {
                val p = ensureOpen(emptyList())
                val body = coordinator.setServers(session, request, AgyMessageSink(::agy))
                look = (look ?: p.look).copy(mcpServers = mcpState.initServers())
                p.frames.controlOk(id, body)
            }
            else -> otherControl(id, subtype, request)
        }
    }

    private suspend fun setModelControl(id: String, request: JsonObject): JsonObject? {
        val requested = JsonScalars.str(request, "model").orEmpty()
        if (requested.isNotEmpty() && !requested.startsWith(ANTIGRAVITY_MODEL_PREFIX)) {
            switchingOut = true
            if (prompts.isNotEmpty()) {
                session?.cancel()
                questions?.dropped(session)
            }
            return null
        }
        val p = ensureOpen(emptyList())
        val refusal = session?.model(requested)
        if (refusal == null) {
            look = (look ?: p.look).copy(model = requested)
            turn?.draw?.model = requested
        }
        return refusal?.let { p.frames.controlError(id, it) } ?: p.frames.controlOk(id)
    }

    private suspend fun otherControl(id: String, subtype: String?, request: JsonObject): JsonObject {
        val p = ensureOpen(emptyList())
        return when (subtype) {
            "interrupt" -> {
                if (prompts.isNotEmpty()) {
                    session?.cancel()
                    questions?.dropped(session)
                }
                p.frames.controlOk(id)
            }
            "set_permission_mode", "apply_flag_settings" -> {
                val mode = JsonScalars.str(request, "mode")
                val refusal = if (mode != null) {
                    applyMode(p, mode)
                } else {
                    JsonScalars.str(request["settings"] as? JsonObject, "effortLevel")?.let { session?.effort(it) }
                }
                refusal?.let { p.frames.controlError(id, it) } ?: p.frames.controlOk(id)
            }
            else -> p.frames.controlError(id, "$subtype is not offered by a session on agy")
        }
    }

    private suspend fun applyMode(p: DesktopParts, mode: String): String? {
        val blocked = p.look.permissionMode == BYPASS_MODE && mode != BYPASS_MODE
        val refusal = if (blocked) STARTED_BYPASSING else session?.mode(mode)
        if (refusal == null) look = (look ?: p.look).copy(permissionMode = mode)
        return refusal
    }

    private suspend fun user(line: JsonObject) {
        val p = ensureOpen(emptyList())
        val uuid = JsonScalars.str(line, "uuid") ?: UUID.randomUUID().toString()
        val content = (line["message"] as? JsonObject)?.get("content") ?: JsonPrimitive("")
        p.out(p.frames.replay(line, uuid))
        p.transcript.user(uuid, content)
        if (prompts.isEmpty()) begin(p)
        val handoff = pendingHandoff
        pendingHandoff = null
        val prompt = session?.prompt(content, handoff)
        if (prompt != null) {
            prompts += prompt
            return
        }
        if (prompts.isNotEmpty()) return
        val reason = unusable ?: AGY_ENDED
        turn?.say(reason)
        finish(reason)
    }

    private fun begin(p: DesktopParts) {
        startedAt = clock()
        usage = lists.usage(null)
        stop = null
        val current = (look ?: p.look).copy(commands = commands, mcpServers = mcpState.initServers())
        look = current
        p.out(p.frames.init(current))
        p.record.write(busy = true)
    }

    private fun answeredPrompt(message: JsonObject) {
        val error = message["error"] as? JsonObject
        val result = message["result"] as? JsonObject
        stop = JsonScalars.str(result, "stopReason") ?: stop
        (result?.get("usage") as? JsonObject)?.let { usage = lists.usage(it) }
        val failure = error?.let {
            if (JsonScalars.int(it, "code") == AUTH_REQUIRED) AGY_SIGN_IN else "agy: ${JsonScalars.str(it, "message")}"
        }
        if (failure != null) turn?.say(failure)
        if (prompts.isEmpty()) finish(failure)
    }

    private fun finish(error: String?) {
        val p = checkNotNull(parts)
        val cancelled = stop == STOP_CANCELLED || error != null
        turn?.end(stop.takeUnless { it == STOP_CANCELLED || it == STOP_MAX_TURN_REQUESTS }, cancelled = cancelled)
        p.out(p.frames.result(TurnEnd(stop, error, clock() - startedAt, usage, (look ?: p.look).model, window)))
        p.record.write(busy = false)
    }

    private fun update(update: JsonObject) {
        when (JsonScalars.str(update, "sessionUpdate")) {
            "available_commands_update" -> {
                commands = lists.commands(update)
                look = look?.copy(commands = commands)
            }
            "usage_update" -> {
                JsonScalars.long(update, "used")?.let {
                    used = it
                    turn?.draw?.used = it
                }
                JsonScalars.long(update, "size")?.let { window = it }
            }
            else -> {
                session?.heard(update)
                if (prompts.isNotEmpty()) turn?.update(update) else turn?.afterTurn(update)
            }
        }
    }
}

private const val STOP_CANCELLED = "cancelled"
private const val STOP_MAX_TURN_REQUESTS = "max_turn_requests"
private const val AGY_ENDED = "agy has ended"
private const val STARTED_BYPASSING =
    "this session's agy was started without permission prompts; start a new session to have them"
