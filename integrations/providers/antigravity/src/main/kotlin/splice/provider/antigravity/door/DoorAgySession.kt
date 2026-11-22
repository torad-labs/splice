// NEW: desktop-antigravity (door chat). One live `agy acp` session serving multi-turn Claude
// Desktop Chat HTTP turns through Splice's door. Runs in an empty scratch directory, denies all
// built-in agy filesystem/shell permissions, and allows only our virtual loopback MCP server so
// Desktop Chat's own tools work via pause-and-resume across HTTP requests.
package splice.provider.antigravity.door

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.provider.antigravity.AGY_SIGN_IN
import splice.provider.antigravity.AcpConnection
import splice.provider.antigravity.AgyFolders
import splice.provider.antigravity.AgyHello
import splice.provider.antigravity.AgyOpen
import splice.provider.antigravity.AgyOpener
import splice.provider.antigravity.AgyScratch
import splice.provider.antigravity.AgySession
import java.io.IOException
import java.nio.file.Path

/** One pooled `agy acp` session serving HTTP turns for one Desktop Chat conversation. */
internal class DoorAgySession(
    private val agyCommand: List<String>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    private val initialTools: List<AnthropicToolDef>,
    private val clock: WallClock,
) : AutoCloseable {
    private val mutex = Mutex()
    private var scratch: AgyScratch? = null
    private val bridge = DoorToolBridge()
    private var connection: AcpConnection? = null
    private var session: AgySession? = null
    private var activePromptId: Long? = null
    private var processedMessages: Int = 0
    private val toolNamesAtStart: Set<String> = initialTools.mapTo(HashSet()) { it.name }

    @Volatile
    var lastUsedMs: Long = clock()
        private set

    @Volatile
    var dead: Boolean = false
        private set

    /** True when this session cannot cleanly continue [ask] and should be replaced by a fresh one. */
    fun needsFreshSession(ask: DoorAgyAsk): Boolean {
        val resumingTools = activePromptId != null &&
            ask.trailingToolResults.isNotEmpty() &&
            bridge.matchesAny(ask.trailingToolResults)
        return when {
            dead -> true
            resumingTools -> false
            activePromptId != null -> true
            processedMessages > 0 && ask.transcript.messageCount <= processedMessages -> true
            else -> ask.toolNames.toSet() != toolNamesAtStart
        }
    }

    /** Runs one HTTP `/v1/messages` turn against this session (either fresh prompt or tool_result resume). */
    suspend fun runTurn(ask: DoorAgyAsk, sink: DoorAgySink): DoorAgyTurnResult = mutex.withLock {
        lastUsedMs = clock()
        val activeSession = ensureOpen(ask)
            ?: return@withLock DoorAgyTurnResult.Failed("agy session could not be opened")
        bridge.setTools(ask.tools)
        val existingPrompt = activePromptId
        val hasPendingResults = ask.trailingToolResults.isNotEmpty() && bridge.matchesAny(ask.trailingToolResults)
        val promptId = if (existingPrompt != null && hasPendingResults) {
            bridge.supplyResults(ask.trailingToolResults)
            existingPrompt
        } else {
            startFreshPrompt(activeSession, ask)
                ?: return@withLock DoorAgyTurnResult.Failed("agy ended before accepting prompt")
        }
        activePromptId = promptId
        val driver = PromptDriver(activeSession, promptId, initialTools, bridge, sink)
        val outcome = driver.drive()
        settleOutcome(outcome, ask)
        outcome
    }

    override fun close() {
        dead = true
        activePromptId = null
        Cancellables.runCatchingBestEffort { bridge.close() }
        Cancellables.runCatchingBestEffort { connection?.close() }
        connection = null
        session = null
        scratch?.remove(log)
        scratch = null
    }

    private fun settleOutcome(outcome: DoorAgyTurnResult, ask: DoorAgyAsk) {
        lastUsedMs = clock()
        when (outcome) {
            is DoorAgyTurnResult.Completed -> if (!outcome.hasToolUse) {
                activePromptId = null
                processedMessages = ask.transcript.messageCount + 1
            }
            is DoorAgyTurnResult.Failed -> {
                activePromptId = null
                dead = true
            }
        }
    }

    private suspend fun ensureOpen(ask: DoorAgyAsk): AgySession? {
        val current = session
        if (current != null) return current
        bridge.setTools(initialTools)
        val folder = makeScratchFolder()
        val conn = folder?.let { startConnection(it) }
        return if (folder != null && conn != null) {
            initializeAndOpen(AgyOpener(conn, folder.toString(), OPEN_TIMEOUT_MS), ask)
        } else {
            null
        }
    }

    private fun makeScratchFolder(): Path? {
        val made = AgyFolders().make().also { scratch = it }
        if (made.path == null) {
            dead = true
            log("[antigravity] door chat scratch failed: ${made.reason}\n")
        }
        return made.path
    }

    private suspend fun initializeAndOpen(opener: AgyOpener, ask: DoorAgyAsk): AgySession? {
        val (hello, refusal) = opener.sayHello()
        if (hello == null) {
            dead = true
            log("[antigravity] door chat initialize failed: ${refusal ?: AGY_SIGN_IN}\n")
            return null
        }
        return finishOpenSession(opener, hello, ask)
    }

    private fun startConnection(folder: Path): AcpConnection? = try {
        AcpConnection(agyCommand, folder, folder.resolve("agy.err").toFile(), scope, io).also { connection = it }
    } catch (e: IOException) {
        dead = true
        log("[antigravity] door chat could not start agy: ${SafeFailureText.render(e)}\n")
        null
    }

    private suspend fun finishOpenSession(opener: AgyOpener, hello: AgyHello, ask: DoorAgyAsk): AgySession? {
        val mcpServers = if (initialTools.isNotEmpty()) {
            buildJsonArray { add(bridge.acpServerSpec()) }
        } else {
            JsonArray(emptyList())
        }
        val opened = opener.openSession(
            resume = null,
            hello = hello,
            mcpServers = mcpServers,
            serverNames = if (initialTools.isNotEmpty()) listOf(CHAT_MCP_SERVER) else emptyList(),
            log = log,
        )
        return when (val open = opened.open) {
            is AgyOpen.Ready -> {
                session = open.session
                applySelection(open.session, ask)
                open.session
            }
            is AgyOpen.Unusable -> {
                dead = true
                log("[antigravity] door chat open failed: ${open.reason}\n")
                null
            }
        }
    }

    private suspend fun applySelection(agy: AgySession, ask: DoorAgyAsk) {
        agy.model(ask.model)
        ask.effort?.let { agy.effort(it) }
    }

    private suspend fun startFreshPrompt(agy: AgySession, ask: DoorAgyAsk): Long? {
        applySelection(agy, ask)
        val prefix = if (processedMessages == 0) ask.transcript.openingPrefix else null
        val content = ask.latestUserContent ?: JsonPrimitive("")
        return agy.prompt(content, prefix)
    }
}

/** Drives one active ACP prompt until it finishes or pauses on bridged MCP tool calls. */
private class PromptDriver(
    private val agy: AgySession,
    private val promptId: Long,
    private val initialTools: List<AnthropicToolDef>,
    private val bridge: DoorToolBridge,
    private val sink: DoorAgySink,
) {
    private val toolCalls = Channel<BridgedToolCall>(Channel.UNLIMITED)
    private val counts = UsageCounts()

    suspend fun drive(): DoorAgyTurnResult {
        var pausedForTools = false
        bridge.beginTurn { call -> toolCalls.trySend(call) }
        try {
            val outcome = stepLoop()
            pausedForTools = outcome is DoorAgyTurnResult.Completed && outcome.hasToolUse
            return outcome
        } catch (cancelled: CancellationException) {
            if (!pausedForTools) cancelOnDisconnect()
            throw cancelled
        } finally {
            bridge.endTurn()
        }
    }

    private suspend fun stepLoop(): DoorAgyTurnResult {
        while (true) {
            when (val step = nextStep()) {
                is LoopStep.ToolCall -> return pauseForTools(step.call)
                is LoopStep.AcpMessage -> handleAcpMessage(step.message)?.let { return it }
                LoopStep.AcpClosed -> return DoorAgyTurnResult.Failed("agy closed before finishing the turn")
            }
        }
    }

    private suspend fun pauseForTools(first: BridgedToolCall): DoorAgyTurnResult {
        sink.onToolUse(first.id, first.name, first.inputJson)
        while (true) {
            val next = withTimeoutOrNull(PARALLEL_TOOL_BATCH_MS) { toolCalls.receive() } ?: break
            sink.onToolUse(next.id, next.name, next.inputJson)
        }
        return DoorAgyTurnResult.Completed(
            hasToolUse = true,
            incomplete = false,
            inputTokens = counts.input,
            outputTokens = counts.output,
        )
    }

    private suspend fun cancelOnDisconnect() {
        withContext(NonCancellable) {
            bridge.cancelPending("request cancelled")
            agy.cancel()
        }
    }

    private suspend fun nextStep(): LoopStep = select {
        toolCalls.onReceive { LoopStep.ToolCall(it) }
        agy.incoming.onReceiveCatching { res ->
            val msg = res.getOrNull()
            if (msg == null) LoopStep.AcpClosed else LoopStep.AcpMessage(msg)
        }
    }

    private suspend fun handleAcpMessage(msg: JsonObject): DoorAgyTurnResult? {
        val method = JsonScalars.str(msg, "method")
        if (method != null) {
            handleAgentMessage(method, msg)
            return null
        }
        return if (JsonScalars.long(msg, "id") == promptId) finishPromptResult(msg) else null
    }

    private fun finishPromptResult(msg: JsonObject): DoorAgyTurnResult {
        val error = msg["error"] as? JsonObject
        if (error != null) {
            return DoorAgyTurnResult.Failed(JsonScalars.str(error, "message") ?: "agy prompt failed")
        }
        val result = msg["result"] as? JsonObject
        val stop = JsonScalars.str(result, "stopReason")
        val usage = result?.get("usage") as? JsonObject
        val input = JsonScalars.long(usage, "inputTokens") ?: counts.input
        val output = JsonScalars.long(usage, "outputTokens") ?: counts.output
        return DoorAgyTurnResult.Completed(
            hasToolUse = false,
            incomplete = stop == "max_tokens",
            inputTokens = input,
            outputTokens = output,
        )
    }

    private suspend fun handleAgentMessage(method: String, msg: JsonObject) {
        val reqId = msg["id"]
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        when (method) {
            "session/update" -> handleSessionUpdate(params["update"] as? JsonObject)
            "session/request_permission" -> if (reqId != null) answerPermission(reqId, params)
            else -> if (reqId != null) agy.answer(reqId, null)
        }
    }

    private suspend fun handleSessionUpdate(update: JsonObject?) {
        if (update == null) return
        agy.heard(update)
        when (JsonScalars.str(update, "sessionUpdate")) {
            "agent_thought_chunk" -> {
                val text = JsonScalars.str(update["content"] as? JsonObject, "text").orEmpty()
                if (text.isNotEmpty()) sink.onThinking(text)
            }
            "agent_message_chunk" -> {
                val text = JsonScalars.str(update["content"] as? JsonObject, "text").orEmpty()
                if (text.isNotEmpty()) sink.onText(text)
            }
            "usage_update" -> {
                val used = JsonScalars.long(update, "used") ?: 0L
                if (used > 0L) counts.input = used
            }
        }
    }

    /** Allows only our virtual Desktop Chat MCP server tools; rejects every built-in agy shell/file tool. */
    private fun answerPermission(reqId: JsonElement, params: JsonObject) {
        val options = params["options"] as? JsonArray ?: JsonArray(emptyList())
        val call = params["toolCall"] as? JsonObject
        val name = JsonScalars.str(call, "name").orEmpty()
        val isBridged = name.startsWith(CHAT_MCP_TOOL_PREFIX) ||
            initialTools.any { name == it.name || name.endsWith("_${it.name}") }
        val kind = if (isBridged) PERMISSION_ALLOW else PERMISSION_REJECT
        val pick = options.mapNotNull { it as? JsonObject }.let { offered ->
            offered.firstOrNull { JsonScalars.str(it, "kind") == "${kind}_once" }
                ?: offered.firstOrNull { JsonScalars.str(it, "kind")?.startsWith(kind) == true }
        }
        agy.answer(reqId, JsonScalars.str(pick, "optionId"))
    }

    private data class UsageCounts(var input: Long = 0L, var output: Long = 0L)

    private sealed class LoopStep {
        data class ToolCall(val call: BridgedToolCall) : LoopStep()
        data class AcpMessage(val message: JsonObject) : LoopStep()
        data object AcpClosed : LoopStep()
    }
}

// why: gives `agy acp` up to 30 seconds to initialize and connect our virtual loopback MCP server.
private const val OPEN_TIMEOUT_MS = 30_000L

// why: short coalescing window to collect parallel MCP tool calls emitted together by agy into one tool_use turn.
private const val PARALLEL_TOOL_BATCH_MS = 40L
private const val PERMISSION_ALLOW = "allow"
private const val PERMISSION_REJECT = "reject"
