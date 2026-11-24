// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). A Claude Desktop session running on agy:
// what each line Desktop writes does on agy, and what each message agy writes shows in Desktop.
package splice.provider.antigravity

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.provider.antigravity.hooks.AcpLoginPrompt
import splice.provider.antigravity.hooks.AcpSessionHooks
import splice.provider.antigravity.peer.DesktopPeerInbox
import splice.provider.antigravity.peer.DesktopPeerMessage
import splice.provider.antigravity.peer.DesktopPeerOutcome
import splice.provider.antigravity.skills.SkillInvocation
import splice.provider.antigravity.skills.SkillShelf
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What a Desktop session has from its start, before agy opens: its local [skills] and Desktop's [out]; and the
 *  [DesktopParts] it builds once the session id is known (either from Desktop's flags or agy's `session/new`). */
internal interface PartsFactory {
    val skills: SkillShelf
    val out: DesktopOut
    fun build(sessionId: String): DesktopParts
}

/** The parts of a Desktop session that outlive its turns. */
internal data class DesktopParts(
    val frames: DesktopFrames,
    val out: DesktopOut,
    val transcript: ClaudeTranscript,
    val record: ClaudeSessionRecord,
    val look: SessionLook,
    val peerInbox: DesktopPeerInbox? = null,
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

    /** Opens the session record and peer inbox with the session, and closes them (record file, key file, socket) when
     *  the session ends. */
    val lifecycle = SessionLifecycleGate(hooks)

    @Volatile
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
    private val invocation = SkillInvocation()
    private var peersClosed = false

    /** What the `system/init` said on `initialize`, until the first turn begins. */
    private var announced: SessionLook? = null

    private var agyCommands: List<AgyCommand> = emptyList()

    override val agy: AgySession? get() = session
    override val skills: SkillShelf get() = partsFactory.skills
    override val commands: List<AgyCommand> get() = lists.mergeCommands(agyCommands, skills.current.typed)
    override val nativeCommands: Set<String> get() = agyCommands.mapTo(HashSet(), AgyCommand::name)

    /** The session's frames; before agy opens, frames under the id Desktop gave, which a control answer (naming no
     *  session) is all they build. */
    override val frames: DesktopFrames
        get() = parts?.frames ?: DesktopFrames(claude.sessionId ?: claude.resume.orEmpty())

    /** True once Desktop asked to switch to a non-Antigravity model, so the launch pipe hands off to Claude Code. */
    var switchingOut: Boolean = false
        private set

    /** True when the session should exit cleanly (after switching out and finishing any in-flight turn). */
    val shouldExit: Boolean get() = switchingOut && prompts.isEmpty()

    /** agy's messages, or null while there is no agy to hear from. */
    val fromAgy get() = session?.incoming

    /** Authenticated peer UDS messages, or null while no inbox is open. */
    val fromPeers: ReceiveChannel<DesktopPeerMessage>? get() = if (peersClosed) null else parts?.peerInbox?.incoming

    /** One line Desktop wrote. */
    suspend fun desktop(line: String) {
        val message = JsonScalars.objectOrNull(Json, line) ?: return
        when (JsonScalars.str(message, "type")) {
            "control_request" -> {
                val reply = controls.handle(
                    JsonScalars.str(message, "request_id").orEmpty(),
                    message["request"] as? JsonObject ?: JsonObject(emptyMap()),
                )
                if (reply != null) (parts?.out ?: partsFactory.out)(reply)
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
        look = current
        if (!lifecycle.open(built, resolvedId, fresh = claude.resume == null)) return built
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

    override fun announce() {
        val p = checkNotNull(parts)
        val now = current
        look = now
        p.out(p.frames.init(now))
        announced = now
    }

    override fun cancelOrSwitch(switchOut: Boolean) {
        if (switchOut) switchingOut = true
        if (prompts.isNotEmpty()) {
            session?.cancel()
            questions?.dropped(session)
        }
    }

    /** One cross-session peer message (or null once the inbox closed): replays into Desktop + transcript and steers
     *  agy's prompt directly, bypassing [user] so slash-command hooks (e.g. `/login`) never intercept peer frames. */
    suspend fun peerMessage(message: DesktopPeerMessage?) {
        if (message == null) {
            peersClosed = true
            return
        }
        if (message.isExpired) return
        try {
            val outcome = Cancellables.runCatchingCancellable {
                val p = if (switchingOut) null else ensureOpen(emptyList())
                if (!message.claimForDelivery()) {
                    null
                } else {
                    val uuid = UUID.randomUUID().toString()
                    val content = JsonPrimitive(message.content)
                    val delivered = p != null && session != null &&
                        submitUser(p, message.userFrame(uuid, content), uuid, content, message)
                    message.outcomeFor(delivered, switchingOut, unusable ?: AGY_ENDED)
                }
            }.getOrElse { failure ->
                DesktopPeerOutcome.Dropped("delivery failed: ${SafeFailureText.render(failure)}")
            }
            outcome?.let(message.outcome::complete)
        } finally {
            message.abortIfUnsettled(PEER_SESSION_CLOSED)
        }
    }

    private suspend fun user(line: JsonObject) {
        val p = ensureOpen(emptyList())
        val uuid = JsonScalars.str(line, "uuid") ?: UUID.randomUUID().toString()
        val content = (line["message"] as? JsonObject)?.get("content") ?: JsonPrimitive("")
        if (!AcpLoginPrompt.matches(content)) {
            submitUser(p, line, uuid, content)
            return
        }
        // Desktop's `/login` is answered locally with Console Accounts, never sent to agy.
        p.out(p.frames.replay(line, uuid))
        p.transcript.user(uuid, content)
        if (prompts.isNotEmpty()) return
        val reply = hooks.login.open()
        begin(p)
        turn?.say(reply)
        finish(null)
    }

    private fun submitUser(
        p: DesktopParts,
        line: JsonObject,
        uuid: String,
        content: JsonElement,
        peer: DesktopPeerMessage? = null,
    ): Boolean {
        p.out(p.frames.replay(line, uuid))
        p.transcript.user(uuid, content)
        if (prompts.isEmpty()) begin(p)
        if (peer?.beginSubmit() == false) {
            if (prompts.isEmpty()) p.record.write(busy = false)
            return false
        }
        val handoff = pendingHandoff
        pendingHandoff = null
        val prompt = session?.prompt(invocation.expand(content, skills.current.typed, nativeCommands), handoff)
        return if (prompt != null) {
            prompts += prompt
            true
        } else {
            if (prompts.isEmpty()) {
                val reason = unusable ?: AGY_ENDED
                turn?.say(reason)
                finish(reason)
            }
            false
        }
    }

    /** A turn begins with `system/init`, as each turn of Claude Code's stream-json does, except the first turn after
     *  `initialize` said it while its model, mode, commands and skills still stand (an MCP server that connected in
     *  between is no reason to say it twice). */
    private fun begin(p: DesktopParts) {
        startedAt = clock()
        usage = lists.usage(null)
        stop = null
        val now = current
        if (announced?.copy(mcpServers = now.mcpServers) != now) announce()
        announced = null
        p.record.write(busy = true)
    }

    /** The session as `system/init` says it now: its model and mode, the merged commands, the local skills and the
     *  MCP servers. */
    private val current: SessionLook
        get() = (look ?: checkNotNull(parts).look).copy(
            commands = commands,
            skills = skills.current.skillNames,
            mcpServers = mcpState.initServers(),
        )

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
private const val PEER_SESSION_CLOSED = "session closed"

// why: bounds JVM shutdown hook waiting for an in-flight open/teardown so a wedged cleanup cannot hang exit forever.
private const val SHUTDOWN_SETTLE_MS = 2_000L

/** Coordinates session startup (`hooks.opened`), teardown (`hooks.ended`), and record/inbox cleanup in one atomic
 *  state machine so shutdown can never emit `SESSION_END` without `SESSION_START`, emit `END` before `START`, return
 *  from a JVM shutdown hook while startup or teardown cleanup is still in flight (up to [settleMs]), or fire `END`
 *  twice. */
internal class SessionLifecycleGate(
    private val hooks: AcpSessionHooks,
    private val settleMs: Long = SHUTDOWN_SETTLE_MS,
) {
    private sealed class State {
        data object Pending : State()
        class Opening(val owner: Thread, val settled: CountDownLatch) : State()
        data class Opened(val parts: DesktopParts) : State()
        class Closing(val owner: Thread, val settled: CountDownLatch) : State()
        data object Closed : State()
    }

    private val state = AtomicReference<State>(State.Pending)

    fun open(built: DesktopParts, resolvedId: String, fresh: Boolean): Boolean {
        val opening = State.Opening(Thread.currentThread(), CountDownLatch(1))
        if (!state.compareAndSet(State.Pending, opening)) {
            cleanup(built)
            return false
        }
        var started = false
        var stayedOpen = false
        try {
            built.record.write(busy = false)
            if (state.get() === opening) {
                hooks.opened(resolvedId, built.look.cwd, fresh = fresh)
                started = true
            }
        } finally {
            try {
                if (!started) {
                    cleanup(built)
                } else if (state.compareAndSet(opening, State.Opened(built))) {
                    stayedOpen = true
                } else {
                    teardown(built)
                }
            } finally {
                if (!stayedOpen) state.set(State.Closed)
                opening.settled.countDown()
            }
        }
        return stayedOpen
    }

    fun close() {
        var finished = false
        while (!finished) {
            when (val current = state.get()) {
                State.Pending -> finished = state.compareAndSet(current, State.Closed)
                is State.Opening -> {
                    val closing = State.Closing(current.owner, current.settled)
                    if (state.compareAndSet(current, closing)) {
                        awaitSettled(current.owner, current.settled)
                        finished = true
                    }
                }
                is State.Closing -> {
                    awaitSettled(current.owner, current.settled)
                    finished = true
                }
                is State.Opened -> finished = finishOpened(current)
                State.Closed -> finished = true
            }
        }
    }

    private fun finishOpened(current: State.Opened): Boolean {
        val closing = State.Closing(Thread.currentThread(), CountDownLatch(1))
        if (!state.compareAndSet(current, closing)) return false
        try {
            teardown(current.parts)
        } finally {
            state.set(State.Closed)
            closing.settled.countDown()
        }
        return true
    }

    private fun awaitSettled(owner: Thread, settled: CountDownLatch) {
        if (Thread.currentThread() === owner) return
        try {
            settled.await(settleMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun cleanup(current: DesktopParts) {
        current.record.remove()
        current.peerInbox?.close()
    }

    private fun teardown(current: DesktopParts) {
        cleanup(current)
        hooks.ended(current.frames.sessionId)
    }
}
