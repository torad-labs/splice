// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). A Claude Desktop session running on agy:
// what each line Desktop writes does on agy, and what each message agy writes shows in Desktop.
package splice.provider.antigravity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.client.ForegroundToolCall
import splice.core.client.ForegroundToolPhase
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import splice.provider.antigravity.skills.LocalSkillCatalog
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
    val localSkills: LocalSkillCatalog = LocalSkillCatalog(emptyList(), emptyList()),
)

/** Desktop session driven on agy via [coordinator] and [mcpState]; [clock] times turns. */
internal class DesktopSession(
    private val claude: ClaudeArgs,
    private val partsFactory: PartsFactory,
    private val coordinator: AgyMcpCoordinator,
    private val mcpState: McpState,
    private val bridge: McpBridge,
    private val clock: WallClock,
    private val hooks: AcpSessionHooks = AcpSessionHooks(),
) : SessionControlHost {
    private val lists = DesktopLists()
    private val controls = DesktopControl(this, coordinator, mcpState, lists)
    private val prompts = HashSet<Long>()
    private var parts: DesktopParts? = null
    private var turn: AgyTurn? = null
    private var questions: AgyQuestions? = null
    private var session: AgySession? = null
    private var unusable: String? = null
    private var opened = false
    private var look: SessionLook? = null
    private var startedAt = 0L
    private var usage: JsonObject = lists.usage(null)
    private var stop: String? = null
    private var window = AGY_WINDOW
    private var used = 0L
    private var pendingHandoff: String? = null

    private var agyCommands: List<AgyCommand> = emptyList()

    override val agy: AgySession? get() = session
    override val commands: List<AgyCommand>
        get() = lists.mergeCommands(agyCommands, parts?.localSkills?.commands.orEmpty())

    /** True once Desktop asked to switch to a non-Antigravity model, so the launch pipe hands off to Claude Code. */
    var switchingOut: Boolean = false
        private set

    /** True when the session should exit cleanly (after switching out and finishing any in-flight turn). */
    val shouldExit: Boolean get() = switchingOut && prompts.isEmpty()

    /** agy's messages, or null while there is no agy to hear from. */
    val fromAgy get() = session?.incoming

    /** Removes the session record file when the session ends. */
    fun closeRecord() {
        parts?.let { p ->
            hooks.foreground?.record(
                ForegroundToolCall(p.frames.sessionId, null, ForegroundToolPhase.SESSION_END, hooks.foregroundOwner),
            )
            p.record.remove()
        }
    }

    /** One line Desktop wrote. */
    suspend fun desktop(line: String) {
        val message = JsonScalars.objectOrNull(Json, line) ?: return
        when (JsonScalars.str(message, "type")) {
            "control_request" -> {
                val reply = controls.handle(
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
    override fun agy(message: JsonObject) {
        val method = JsonScalars.str(message, "method")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val id = message["id"]
        val active = session
        when {
            method == "session/update" -> (params["update"] as? JsonObject)?.let(::update)
            active == null || id == null -> Unit
            method == "session/request_permission" ->
                questions?.ask(active, id, params, (look ?: parts?.look)?.permissionMode == BYPASS_MODE)
            method != null -> active.refuse(id, method)
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

    override suspend fun ensureOpen(desktopSpecs: List<AgyMcpSpec>): DesktopParts {
        val existing = parts
        if (opened && existing != null) return existing
        opened = true
        val sink = object : AgyMessageSink {
            override fun accept(message: JsonObject) = agy(message)
        }
        val res = coordinator.open(desktopSpecs, sink)
        session = res.session
        unusable = res.unusable
        val resolvedId = claude.sessionId ?: claude.resume ?: res.session?.id ?: UUID.randomUUID().toString()
        val built = partsFactory.build(resolvedId)
        val agyTurn = AgyTurn(
            built.frames,
            built.out,
            built.transcript,
            mcpState,
            hooks.foreground,
            hooks.foregroundOwner,
        )
        agyTurn.draw.model = built.look.model
        agyTurn.draw.used = used
        parts = built
        turn = agyTurn
        questions = AgyQuestions(built.frames, built.out, agyTurn)
        look = built.look.copy(
            commands = commands,
            skills = built.localSkills.skillNames,
            mcpServers = mcpState.initServers(),
        )
        built.record.write(busy = false)
        if (claude.resume == null && built.look.cwd.isNotBlank()) {
            hooks.sessionStart?.started(resolvedId, built.look.cwd)
        }
        hooks.foreground?.record(
            ForegroundToolCall(resolvedId, null, ForegroundToolPhase.SESSION_START, hooks.foregroundOwner),
        )
        if (claude.resume != null) pendingHandoff = LiveHandoff().of(built.transcript.file, res.resumed)
        return built
    }

    override fun syncLook(model: String?, mode: String?) {
        val base = look ?: checkNotNull(parts).look
        look = base.copy(
            model = model ?: base.model,
            permissionMode = mode ?: base.permissionMode,
            mcpServers = mcpState.initServers(),
        )
        if (model != null) turn?.draw?.model = model
    }

    override fun cancelOrSwitch(switchOut: Boolean) {
        if (switchOut) switchingOut = true
        if (prompts.isNotEmpty()) {
            session?.cancel()
            questions?.dropped(session)
        }
    }

    private suspend fun user(line: JsonObject) {
        val p = ensureOpen(emptyList())
        val uuid = JsonScalars.str(line, "uuid") ?: UUID.randomUUID().toString()
        val content = (line["message"] as? JsonObject)?.get("content") ?: JsonPrimitive("")
        p.out(p.frames.replay(line, uuid))
        p.transcript.user(uuid, content)
        if (AcpLoginPrompt.matches(content)) {
            val reply = hooks.login.open()
            if (prompts.isEmpty()) {
                begin(p)
                turn?.say(reply)
                finish(null)
            } else {
                turn?.say(reply)
            }
            return
        }
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
        val current = (look ?: p.look).copy(
            commands = commands,
            skills = p.localSkills.skillNames,
            mcpServers = mcpState.initServers(),
        )
        look = current
        p.out(p.frames.init(current))
        p.record.write(busy = true)
    }

    private fun answeredPrompt(message: JsonObject) {
        val error = message["error"] as? JsonObject
        val result = message["result"] as? JsonObject
        stop = JsonScalars.str(result, "stopReason") ?: stop
        (result?.get("usage") as? JsonObject)?.let { usage = lists.usage(it) }
        if (prompts.isNotEmpty()) return
        val failure = error?.let {
            if (JsonScalars.int(it, "code") == AUTH_REQUIRED) AGY_SIGN_IN else "agy: ${JsonScalars.str(it, "message")}"
        }
        if (failure != null) turn?.say(failure)
        finish(failure)
    }

    private fun finish(error: String?) {
        val p = checkNotNull(parts)
        val close = when {
            error != null -> TurnClose.FAILED
            stop == STOP_CANCELLED -> TurnClose.CANCELLED
            else -> TurnClose.NORMAL
        }
        turn?.end(stop.takeUnless { it == STOP_CANCELLED || it == STOP_MAX_TURN_REQUESTS }, close)
        p.out(p.frames.result(TurnEnd(stop, error, clock() - startedAt, usage, (look ?: p.look).model, window)))
        p.record.write(busy = false)
    }

    private fun update(update: JsonObject) {
        when (JsonScalars.str(update, "sessionUpdate")) {
            "available_commands_update" -> {
                agyCommands = lists.commands(update)
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
