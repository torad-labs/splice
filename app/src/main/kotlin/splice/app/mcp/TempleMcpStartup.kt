package splice.app.mcp

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.HUB_FRONT_CONNECT_MS
import splice.client.mcp.TEMPLE_MCP_NAME
import splice.client.mcp.TempleMcpEntry
import splice.control.mcp.McpHost
import splice.control.mcp.McpReply
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path

/** Starts the registered local MCP process independently of Temple's app, then installs its named native client
 *  entry. Reused by daemon boot and the installer's management call, without restarting existing sessions. */
internal class TempleMcpStartup(private val paths: StatePaths, private val log: LogSink) {
    suspend fun refresh(host: McpHost, home: Path, frontPort: Int?): McpReply {
        val source = TempleMcpEntry(home, paths = paths)
        if (source.server() == null) return reply(null, registered = false)
        val reachableFront = withContext(ProcessDispatchers().io()) { frontPort?.takeIf(::frontAnswers) }
        val failure = start(host, source, reachableFront)
        val clients = TempleMcpClients(source)
        val nativeHome = clients.nativeHome(splice.core.config.UserHome.dir())
        val nativeFailure = nativeHome?.let {
            NativeCodexMcp(paths, log).install(it, clients.nativeEntry(host.sharing, reachableFront))
        }
        val error = failure ?: nativeFailure
        if (error != null) log("[mcp-service] $error\n")
        return reply(error, registered = true)
    }

    private suspend fun start(host: McpHost, source: TempleMcpEntry, frontPort: Int?): String? {
        if (TEMPLE_MCP_NAME !in host.sharing.plan(source.entries()).hosted) return null
        return if (frontPort != null) {
            val started = host.hub?.start("{\"server\":\"$TEMPLE_MCP_NAME\"}")
            if (started?.status == HttpStatus.OK) null else "Temple MCP could not start through Splice's hub."
        } else {
            val started = host.probe.test(TEMPLE_MCP_NAME)
            if (started.ok) null else "Temple MCP could not start through Splice's host (${started.kind})."
        }
    }

    /** Installed binaries alone do not mean the separately socket-activated front is listening. */
    private fun frontAnswers(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), HUB_FRONT_CONNECT_MS) }
        true
    } catch (_: IOException) {
        false
    }

    private fun reply(error: String?, registered: Boolean): McpReply = McpReply(
        if (error == null) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE,
        buildJsonObject {
            put("ok", error == null)
            put("registered", registered)
            error?.let { put("error", it) }
        }.toString(),
    )
}
