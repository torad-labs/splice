package splice.app.peer.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.sessions.peer.codex.TurnStart
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker

// why: owner discovery is a local IPC exchange; this matches the app's own five-second discovery deadline.
private const val DESKTOP_DISCOVERY_MS = 5_000L

// why: receiving the first bounded stream snapshot may take longer than owner discovery.
private const val DESKTOP_SNAPSHOT_MS = 15_000L

// why: lifecycle notifications are tiny; retain a bounded backlog while the helper updates its registry.
private const val LIFECYCLE_BACKLOG = 32

// why: a desktop client can remain alive after unloading one chat; periodically check that exact thread's owner.
private const val OWNER_CHECK_MS = 10_000L

// why: these are the installed desktop IPC protocol versions, not app-server JSON-RPC versions.
private const val COORDINATION_VERSION = 1
private const val STREAM_VERSION = 11
private const val STREAM_CHANGED = "thread-stream-state-changed"
private const val FOLLOWING_CHANGED = "thread-stream-following-changed"
private const val THREAD_ID = "threadId"

/** Reaches a desktop-owned thread through its existing owner, never through another app-server's resume path. */
internal class CodexDesktopThread(
    private val rpc: CodexIpcRpc,
    private val thread: String,
    private val ticker: Ticker = ProcessTicker(),
) : CodexLiveThread {
    private val events = Channel<CodexEvent>(LIFECYCLE_BACKLOG)
    private val firstStatus = CompletableDeferred<String?>()
    private val state = CodexDesktopState()

    @Volatile
    private var owner: String? = null

    private var lastStatus: String? = null
    override val notifications: ReceiveChannel<CodexEvent> get() = events

    override suspend fun read(): String = coroutineScope {
        val reading = async { rpc.read() }
        val ownership = launch { watchOwner() }
        try {
            for (event in rpc.events) observe(event)
            reading.await()
        } finally {
            ownership.cancel()
            rpc.close()
            firstStatus.complete(null)
            events.close()
        }
    }

    /** A read-only owner probe. It neither follows history nor starts/resumes the thread. */
    suspend fun discover(): String? {
        val init = rpc.request("initialize", buildJsonObject { put("clientType", "splice") }, 0, DESKTOP_DISCOVERY_MS)
        if (init !is CodexIpcAnswer.Result) return reason(init)
        val found = rpc.request("thread-owner-discovery", identity(), COORDINATION_VERSION, DESKTOP_DISCOVERY_MS)
        if (found !is CodexIpcAnswer.Result) return reason(found)
        owner = JsonScalars.str(found.value, "handledByClientId")
        return if (owner == null) "the desktop did not identify the thread owner" else null
    }

    override suspend fun greet(): CodexGreeting {
        discover()?.let { return CodexGreeting.Refused(it) }
        val following = buildJsonObject {
            put("hostId", "local")
            put("conversationId", thread)
            put("following", true)
        }
        rpc.broadcast(FOLLOWING_CHANGED, following, COORDINATION_VERSION, listOfNotNull(owner))?.let {
            return CodexGreeting.Refused(it)
        }
        val status = withTimeoutOrNull(DESKTOP_SNAPSHOT_MS) { firstStatus.await() }
        return status?.let { CodexGreeting.Loaded(it) }
            ?: CodexGreeting.Refused("the desktop owner did not provide the thread's current status")
    }

    override suspend fun startTurn(clientId: String, text: String): TurnStart {
        val target = owner ?: return TurnStart.NotSent("the desktop thread has no connected owner")
        return CodexDesktopTurn(rpc, thread, target).start(clientId, text)
    }

    // Desktop IPC has no read-only, complete message-history query. A lost hand-over stays unconfirmed after restart;
    // the journal already guarantees that such a message is never sent again.
    override suspend fun holds(clientId: String): Boolean? = null

    override fun mine(params: JsonObject?): Boolean = JsonScalars.str(params, THREAD_ID) == thread

    override fun close() {
        rpc.close()
    }

    private suspend fun observe(event: JsonObject) {
        val params = event["params"] as? JsonObject ?: return
        when (JsonScalars.str(event, "method")) {
            "client-status-changed" -> {
                val disconnected = JsonScalars.str(params, "status") == "disconnected"
                if (JsonScalars.str(params, "clientId") == owner && disconnected) {
                    events.send(CodexEvent("thread/closed", buildJsonObject { put(THREAD_ID, thread) }))
                    firstStatus.complete(null)
                    close()
                }
            }
            STREAM_CHANGED -> observeState(event, params)
        }
    }

    private suspend fun observeState(event: JsonObject, params: JsonObject) {
        val sameThread =
            JsonScalars.str(params, "hostId") == "local" && JsonScalars.str(params, "conversationId") == thread
        if (!sameThread || JsonScalars.str(event, "sourceClientId") != owner) {
            return
        }
        val status = readStatus(event, params) ?: return
        firstStatus.complete(status)
        if (lastStatus != status) {
            lastStatus = status
            events.send(
                CodexEvent(
                    "thread/status/changed",
                    buildJsonObject {
                        put(THREAD_ID, thread)
                        put("status", buildJsonObject { put("type", status) })
                    },
                ),
            )
        }
    }

    private fun readStatus(event: JsonObject, params: JsonObject): String? {
        if (JsonScalars.int(event, "version") != STREAM_VERSION) {
            close()
            return null
        }
        val change = params["change"] as? JsonObject ?: return null
        return try {
            state.accept(change)
        } catch (_: IllegalStateException) {
            close()
            null
        }
    }

    private fun identity(): JsonObject = buildJsonObject {
        put("hostId", "local")
        put("conversationId", thread)
    }

    private suspend fun watchOwner() {
        while (ticker.awaitTick(OWNER_CHECK_MS)) {
            val expected = owner ?: continue
            val answer = rpc.request("thread-owner-discovery", identity(), COORDINATION_VERSION, DESKTOP_DISCOVERY_MS)
            val actual = (answer as? CodexIpcAnswer.Result)?.value?.let { JsonScalars.str(it, "handledByClientId") }
            val gone = answer is CodexIpcAnswer.Error && answer.reason == "no-client-found"
            val changed = answer is CodexIpcAnswer.Result && actual != expected
            if (gone || changed) {
                events.send(CodexEvent("thread/closed", buildJsonObject { put(THREAD_ID, thread) }))
                close()
                return
            }
        }
    }

    private fun reason(answer: CodexIpcAnswer): String = when (answer) {
        is CodexIpcAnswer.Result -> "the desktop returned an unexpected response"
        is CodexIpcAnswer.Error -> answer.reason
        is CodexIpcAnswer.Lost -> answer.reason
    }
}
