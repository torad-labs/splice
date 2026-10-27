// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): a codex peer's connection to the app-server that runs
// its thread. The app-server is the one listening on the Codex home's control socket,
// app-server-control/app-server-control.sock, or else the daemon its app-server-daemon/daemon.pid names, found as the
// MCP config finds it. The helper connects, initializes, and reads the thread without its turns: a thread that home's
// app-server has not loaded has no peer. It never starts or resumes a thread.
package splice.app.peer.codex

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.app.mcp.NativeCodexDaemonSocketDiscovery
import splice.app.mcp.NativeCodexSocketPath
import splice.core.GATEWAY_VERSION
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.sessions.peer.codex.CodexBinding
import splice.sessions.peer.codex.CodexThread
import splice.sessions.peer.codex.TurnStart
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path

// why: a thread read, an initialize or a history read answers in well under a second; 15 s is the MCP config dialog's.
private const val READ_TIMEOUT_MS = 15_000L

// why: turn/start answers once the turn is made, before the model runs; a minute covers a loaded app-server.
private const val TURN_TIMEOUT_MS = 60_000L

/** The thread status of a thread its app-server has not loaded, or has unloaded. */
internal const val NOT_LOADED = "notLoaded"
private const val USER_MESSAGE = "userMessage"
private const val THREAD_ID = "threadId"
private const val TYPE = "type"

/** Where a Codex home's app-server listens. */
internal class CodexEndpoints {
    private val sockets = NativeCodexSocketPath()
    private val daemons = NativeCodexDaemonSocketDiscovery()

    /** The control socket when it leads to a socket, else the daemon's listener; null when no app-server runs there. */
    fun find(home: Path): Path? {
        val control = home.resolve("app-server-control").resolve("app-server-control.sock")
        val resolved = try {
            control.toRealPath()
        } catch (_: IOException) {
            null
        }
        return if (resolved != null && sockets.present(resolved)) control else daemons.find(home)?.path
    }
}

/** How opening the thread went. */
internal sealed class CodexOpen {
    /** A WebSocket to the app-server is open; [CodexAppThread.greet] says whether it has the thread. */
    data class Open(val thread: CodexAppThread) : CodexOpen()

    data class Gone(val reason: String) : CodexOpen()
}

/** Opens threads on their app-servers. */
internal class CodexAppServers(private val io: CoroutineDispatcher) {
    private val endpoints = CodexEndpoints()

    /** Connects to [binding]'s app-server and reads its thread. The caller runs [CodexAppThread.read] alongside. */
    suspend fun open(binding: CodexBinding): CodexOpen {
        val path = endpoints.find(Path.of(binding.home))
            ?: return CodexOpen.Gone("no Codex app-server listens for ${binding.home}")
        val socket = try {
            connect(path)
        } catch (failure: IOException) {
            return CodexOpen.Gone("the app-server at $path refused the connection: ${SafeFailureText.render(failure)}")
        }
        return if (socket == null) {
            CodexOpen.Gone("the app-server at $path did not open a WebSocket")
        } else {
            CodexOpen.Open(CodexAppThread(CodexRpc(socket, io), binding.thread))
        }
    }

    private suspend fun connect(path: Path): CodexSocket? = runInterruptible(io) {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(path))
            CodexSocket(channel).takeIf { it.handshake() } ?: run {
                channel.close()
                null
            }
        } catch (failure: IOException) {
            channel.close()
            throw failure
        }
    }
}

/** The thread on its app-server, as the delivery and the helper's watch use it. */
internal class CodexAppThread(private val rpc: CodexRpc, private val thread: String) : CodexThread {
    val notifications: ReceiveChannel<CodexEvent> get() = rpc.notifications

    /** Reads every message until the connection ends; why it ended. Runs alongside everything else. */
    suspend fun read(): String = rpc.read()

    /** Initializes the dialog and reads the thread: its status type, or why it cannot be a peer. */
    suspend fun greet(): CodexGreeting {
        val init = rpc.request(
            "initialize",
            buildJsonObject {
                putJsonObject("clientInfo") {
                    put("name", "splice_peer")
                    put("title", "Splice codex peer")
                    put("version", GATEWAY_VERSION)
                }
                putJsonObject("capabilities") { put("experimentalApi", true) }
            },
            READ_TIMEOUT_MS,
        )
        if (init !is CodexAnswer.Result) return CodexGreeting.Refused("initialize: ${reason(init)}")
        rpc.notify("initialized")?.let { return CodexGreeting.Refused(it) }
        val read = rpc.request("thread/read", threadParams(includeTurns = false), READ_TIMEOUT_MS)
        val status = (read as? CodexAnswer.Result)?.let { statusOf(it.value as? JsonObject) }
        return when {
            read !is CodexAnswer.Result -> CodexGreeting.Refused("thread/read: ${reason(read)}")
            status == null -> CodexGreeting.Refused("thread/read answered no thread status")
            status == NOT_LOADED -> CodexGreeting.Refused("the app-server has not loaded the thread")
            else -> CodexGreeting.Loaded(status)
        }
    }

    override suspend fun startTurn(clientId: String, text: String): TurnStart {
        val params = buildJsonObject {
            put(THREAD_ID, thread)
            put("clientUserMessageId", clientId)
            putJsonArray("input") {
                add(
                    buildJsonObject {
                        put(TYPE, "text")
                        put("text", text)
                        put("text_elements", buildJsonArray { })
                    },
                )
            }
        }
        return when (val answer = rpc.request("turn/start", params, TURN_TIMEOUT_MS)) {
            is CodexAnswer.Result -> {
                val turn = (answer.value as? JsonObject)?.get("turn") as? JsonObject
                JsonScalars.str(turn, "id")?.let { TurnStart.Started(it) }
                    ?: TurnStart.Unknown("Codex answered turn/start without a turn id")
            }
            is CodexAnswer.Error -> TurnStart.Refused(answer.message)
            is CodexAnswer.Lost ->
                if (answer.sent) TurnStart.Unknown(answer.reason) else TurnStart.NotSent(answer.reason)
        }
    }

    override suspend fun holds(clientId: String): Boolean? {
        val answer = rpc.request("thread/read", threadParams(includeTurns = true), READ_TIMEOUT_MS)
        val turns = ((answer as? CodexAnswer.Result)?.value as? JsonObject)
            ?.let { it["thread"] as? JsonObject }?.get("turns") as? JsonArray ?: return null
        return turns.any { turn ->
            ((turn as? JsonObject)?.get("items") as? JsonArray).orEmpty().any { item ->
                val entry = item as? JsonObject
                JsonScalars.str(entry, TYPE) == USER_MESSAGE && JsonScalars.str(entry, "clientId") == clientId
            }
        }
    }

    fun close() {
        rpc.close()
    }

    /** Whether [params] is about this thread. */
    fun mine(params: JsonObject?): Boolean = JsonScalars.str(params, THREAD_ID) == thread

    private fun threadParams(includeTurns: Boolean): JsonObject = buildJsonObject {
        put(THREAD_ID, thread)
        put("includeTurns", includeTurns)
    }

    private fun statusOf(result: JsonObject?): String? =
        ((result?.get("thread") as? JsonObject)?.get("status") as? JsonObject)?.let { JsonScalars.str(it, TYPE) }

    private fun reason(answer: CodexAnswer): String = when (answer) {
        is CodexAnswer.Result -> "an unexpected answer"
        is CodexAnswer.Error -> answer.message
        is CodexAnswer.Lost -> answer.reason
    }
}

/** How the greeting went: the thread is loaded with a status, or why it is no peer. */
internal sealed class CodexGreeting {
    data class Loaded(val status: String) : CodexGreeting()

    data class Refused(val reason: String) : CodexGreeting()
}
