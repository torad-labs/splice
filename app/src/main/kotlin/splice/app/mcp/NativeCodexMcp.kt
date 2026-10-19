package splice.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import splice.core.config.StatePaths
import splice.core.util.LogSink
import java.nio.file.Path

// why: bounds proxy mode before installation falls back to an owned stdio connection.
private const val NATIVE_CODEX_PROXY_TIMEOUT_MS = 1_000L

// why: bounds the owned stdio config connection when neither shared proxy path is available.
private const val NATIVE_CODEX_DIALOG_TIMEOUT_MS = 15_000L

// why: bounds the first attempt through Temple's bridge to the existing Codex control socket.
private const val NATIVE_CODEX_SOCKET_TIMEOUT_MS = 4_000L
private const val NATIVE_CODEX_UNAVAILABLE_REASON = "Codex app-server config API is unavailable."

internal class NativeCodexMcp(
    paths: StatePaths,
    private val log: LogSink,
) {
    private val config = NativeCodexMcpConfig(paths)
    private val rpc = NativeCodexMcpRpc(log)
    private val socket = NativeCodexSocketProxy(paths)

    private data class Connection(val session: NativeCodexMcpRpcSession, val shared: Boolean)

    suspend fun install(home: Path, entry: JsonObject): String? {
        val configFile = home.resolve("config.toml").toAbsolutePath().normalize()
        val connected = connect(home) ?: return NATIVE_CODEX_UNAVAILABLE_REASON
        val session = connected.session
        return try {
            val changed = config.install(session, configFile, entry)
            val status = if (changed) "updated" else "matched"
            log("[mcp-service] Native Codex MCP config $status.\n")
            if (!connected.shared && socket.present(home)) {
                "Native Codex MCP is saved, but its shared daemon could not reload it. Retry the installation."
            } else {
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NativeCodexMcpConfigFailure) {
            log(failure.reason)
            failure.reason
        } catch (failure: NativeCodexMcpRpcFailure) {
            log(failure.reason)
            failure.reason
        } finally {
            session.close()
        }
    }

    private suspend fun connect(home: Path): Connection? {
        val bridge = socket.command(home)?.let { rpc.connect(home, it, NATIVE_CODEX_SOCKET_TIMEOUT_MS) }
        val proxy = bridge ?: rpc.connect(
            home,
            listOf("codex", "app-server", "proxy"),
            NATIVE_CODEX_PROXY_TIMEOUT_MS,
        )
        if (proxy != null) return Connection(proxy, shared = true)
        log("[mcp-service] Native Codex socket unavailable; using an owned stdio config connection.\n")
        return rpc.connect(
            home,
            listOf("codex", "app-server", "--stdio"),
            NATIVE_CODEX_DIALOG_TIMEOUT_MS,
        )?.let { Connection(it, shared = false) }
    }
}
