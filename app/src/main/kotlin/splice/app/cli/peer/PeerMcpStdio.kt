package splice.app.cli.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.app.cli.AdminSupport
import splice.app.control.peer.PEER_MCP_ENDPOINT
import splice.app.control.peer.PEER_MCP_SERVER
import splice.app.control.peer.PeerMcpWire
import splice.client.mcp.MCP_CONFIG_FLAG
import splice.control.mcp.inprocess.InProcessMcpHost
import splice.control.mcp.inprocess.InProcessMcpServer
import splice.core.config.InstallPaths
import splice.core.config.KeyStorePath
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.PostReading
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerStore

/** A stdio session picks its peer from kernel ancestry, never from tool arguments or an inherited key. */
internal class PeerMcpStdio(private val env: EnvReader) {
    private val processes = PeerOwnerProcesses()
    private val protocol = InProcessMcpHost { _, _ -> UnboundPeerTools }
    private val endpoint = "http://127.0.0.1:${AdminSupport.controlPort(env)}$PEER_MCP_ENDPOINT"
    private val settings = KeyStorePath.defaultPath(env).toAbsolutePath().parent
    private var binding: OwnedPeer? = null

    suspend fun run(args: List<String>): Int? {
        if (args.isNotEmpty()) return null
        System.`in`.bufferedReader(Charsets.UTF_8).use { input ->
            while (true) answer(input.readLine() ?: break)?.let(::println)
        }
        return PeerExit.DONE
    }

    suspend fun answer(line: String): String? {
        val request = JsonScalars.objectOrNull(Json, line)
        if (JsonScalars.str(request, "method") != "tools/call") {
            return protocol.post(PEER_MCP_SERVER, null, line, null)?.body
        }
        return forward(line, request?.get("id"))
    }

    private fun forward(line: String, id: JsonElement?): String? {
        val owned = binding ?: resolve()?.also { binding = it }
        val key = owned?.takeIf { processes.live(it.owner) }?.key()
        val result = key?.let { ControlPlaneClient.post(endpoint, it, line, CALL_TIMEOUT_MS) }
        val reply = (result as? PostReading.Answered)?.reply
        if (reply != null && reply.status in ControlPlaneClient.OK_RANGE) return reply.body
        return unavailable(id, result)
    }

    private fun unavailable(id: JsonElement?, result: PostReading?): String? {
        val reason = when (result) {
            PostReading.CutAfterSending -> "the control plane stopped answering; the message may have gone out"
            PostReading.NotConnected -> "the control plane is not reachable"
            is PostReading.Answered, null -> "no live auto-subscribed harness owns this MCP session"
        }
        return id?.let {
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", UnboundPeerTools.unavailable(reason))
            }.toString()
        }
    }

    private fun resolve(): OwnedPeer? {
        // Reopen the store so discovery can see registrations made after stdio was launched.
        val current = PeerStore(settings)
        val owners = current.nativeOwners
        val candidates = current.list().filter { it.name in current.harnesses || it.name in current.codexThreads }
        val namedThread = env(CODEX_THREAD_ENV)
        val match = processes.ancestors().firstNotNullOfOrNull { owner ->
            val matching = candidates.filter { owners[it.name] == owner }
            matching.singleOrNull() ?: matching.singleOrNull { current.codexThreads[it.name]?.thread == namedThread }
                ?.takeIf { namedThread != null }
        } ?: return null
        val owner = owners[match.name] ?: return null
        return OwnedPeer(match.name, match.sessionId, owner, current)
    }

    private data class OwnedPeer(
        val name: String,
        val sessionId: String,
        val owner: NativeProcess,
        val store: PeerStore,
    ) {
        fun key(): String? = store.ownedKeys.read(name, sessionId, owner)
    }
}

/** Discovery needs no peer credential and can finish before a harness's SessionStart hook runs. */
private object UnboundPeerTools : InProcessMcpServer {
    override fun tools(): JsonArray = PeerMcpWire.tools

    override suspend fun call(tool: String, arguments: JsonObject, session: String?): JsonObject =
        unavailable("no live auto-subscribed harness owns this MCP session")

    override fun resources(): JsonArray = JsonArray(emptyList())

    override fun read(uri: String): JsonArray? = null

    fun unavailable(reason: String): JsonObject = buildJsonObject {
        put("isError", true)
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", buildJsonObject { put("status", "unavailable"); put("reason", reason) }.toString())
                },
            )
        }
    }
}

/** The launch carries a command and args only. The command proves its own parent at run time. */
internal class PeerMcpLaunch(env: EnvReader) {
    val command: String = InstallPaths(envReader = env).binDir.resolve("splice").toAbsolutePath().toString()
    val args: List<String> = listOf("peer", "mcp")

    fun claudeArgs(argv: List<String>): List<String> {
        val config = buildJsonObject { putJsonObject("mcpServers") { put(PEER_MCP_SERVER, entry()) } }
        val split = argv.indexOf("--").takeIf { it >= 0 } ?: argv.size
        return argv.take(split) + listOf(MCP_CONFIG_FLAG, config.toString()) + argv.drop(split)
    }

    fun entry(): JsonObject = buildJsonObject {
        put("type", "stdio")
        put("command", command)
        put("args", JsonArray(args.map(::JsonPrimitive)))
    }
}
