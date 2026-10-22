package splice.app.mcp

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Uses Temple's installed Bun/ws transport to reach the existing native daemon. Its control socket is WebSocket;
 *  forwarding stdio lines directly to it leaves initialize unanswered. The owned bridge never starts a daemon. */
internal class NativeCodexSocketProxy(private val paths: StatePaths) {
    private val sockets = NativeCodexDaemonSocketDiscovery()
    private val bridgeCommand = NativeCodexBridgeCommand()

    fun present(home: Path): Boolean = socketPath(home) != null

    fun command(home: Path): List<String>? {
        val socket = socketPath(home) ?: return null
        val registration = TempleMcpEntry(UserHome.dir(), paths = paths).registration()
        val server = registration["server"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            bridgeCommand.create(server, installBridge(), socket)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Prefer this CODEX_HOME's verified daemon listener, retaining the legacy path for older Codex builds. */
    private fun socketPath(home: Path): Path? =
        sockets.find(home) ?: legacyControl(home).takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }

    private fun legacyControl(home: Path): Path =
        home.resolve("app-server-control").resolve("app-server-control.sock")

    private fun installBridge(): Path {
        val resource = javaClass.classLoader.getResourceAsStream("splice/mcp/codex-socket-proxy.js")
            ?: throw IOException("the native Codex socket transport is missing from this Splice build")
        val bytes = resource.use { it.readBytes() }
        val file = paths.stateDir.resolve("mcp-services").resolve("codex-socket-proxy.js")
        Files.createDirectories(file.parent)
        SecureFile.writeAtomic0600(file, bytes.decodeToString())
        return file
    }
}
