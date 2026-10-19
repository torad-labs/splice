package splice.app.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Uses Temple's installed Bun/ws transport to reach the existing native daemon. Its control socket is WebSocket;
 *  forwarding stdio lines directly to it leaves initialize unanswered. The owned bridge never starts a daemon. */
internal class NativeCodexSocketProxy(private val paths: StatePaths) {
    fun present(home: Path): Boolean = Files.exists(control(home), LinkOption.NOFOLLOW_LINKS)

    fun command(home: Path): List<String>? {
        if (!present(home)) return null
        val registration = TempleMcpEntry(UserHome.dir(), paths = paths).registration()
        val server = registration["server"] as? JsonObject ?: JsonObject(emptyMap())
        val bun = JsonScalars.str(server, "command")
        val script = (server["args"] as? JsonArray)?.firstOrNull()?.let(JsonScalars::str)
        if (bun == null || script == null) return null
        return try {
            listOf(bun, installBridge().toString(), control(home).toString(), script)
        } catch (_: IOException) {
            null
        }
    }

    private fun control(home: Path): Path = home.resolve("app-server-control").resolve("app-server-control.sock")

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
