// Key rotation (Oct 26, 2026): the daemon's half of `splice rotate-key`. A management key that was shown somewhere it
// should not have been is replaced while the daemon runs, and no running session is stranded by it:
//
//   1. The key is replaced in memory and on disk (MgmtKey.replace). Every holder reads it per request: the control
//      guard, the console key, and each head's tokens (HeadDeps.HeadTokens). The old key, and the turn key made from
//      it, open nothing from this moment.
//   2. The turn-key header file is rewritten. A session splice launched reads its turn key from it through Claude
//      Code's apiKeyHelper, which Claude Code runs again after a 401 before it sends the same request again, so the
//      session's next turn goes through on its own head. Its status line and hooks read the same file.
//   3. The MCP access key bound to the old key is revoked and a new one published (McpAccessKey). A session that holds
//      the old one reaches its hosted MCP servers again once it is relaunched.
//   4. The pages an older `splice console` and the removed `splice dashboard` left in the state folder are deleted:
//      each sends a browser to an address that carries the old key.
//
// The answer is paths and words. No key is in it, and none is logged.
package splice.app.control

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.client.mcp.McpAccessKey
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files

// The two pages that sent a browser to an address carrying the management key: `splice console` wrote the first until
// Oct 26, 2026 (it now writes a single-use code there), and the removed `splice dashboard` wrote the second.
private const val CONSOLE_PAGE = "console-open.html"
private const val DASHBOARD_PAGE = "dashboard-open.html"

/** Replaces the management key and everything made from it, in one call (`POST /api/mgmt-key/rotate`). */
internal class KeyRotation(
    private val mgmtKey: MgmtKey,
    private val mcpAccess: McpAccessKey,
    private val statePaths: StatePaths,
    private val log: LogSink,
) {
    private val turnKey = TurnKey(mgmtKey)

    /** The key is replaced first: a failure after it is reported in the answer, never undone, because the old key is
     *  the one that was exposed. */
    fun rotate(): JsonObject {
        mgmtKey.replace()
        val header = rewriteHeader()
        val mcp = replaceMcpAccess()
        val removed = listOf(CONSOLE_PAGE, DASHBOARD_PAGE).filter(::deleted)
        log(
            "[mgmt-key] the management key was replaced; the old key and its turn key are refused from now on " +
                "(turn-key file: ${LogSafe.str(header)}, MCP access key: ${LogSafe.str(mcp)}, " +
                "pages removed: ${LogSafe.list(removed)})\n",
        )
        return buildJsonObject {
            put("replaced", true)
            put("key_file", statePaths.mgmtKeyFile.toString())
            put("turn_header", header)
            put("mcp_access", mcp)
            putJsonArray("pages_removed") { removed.forEach { add(it) } }
        }
    }

    private fun rewriteHeader(): String = try {
        turnKey.headerFile()
        HEADER_WRITTEN
    } catch (e: IOException) {
        SafeFailureText.render(e)
    }

    /** Deletes [page] from the state folder when it is there. One that cannot be deleted stays, and is not named. */
    private fun deleted(page: String): Boolean = try {
        Files.deleteIfExists(statePaths.stateDir.resolve(page))
    } catch (_: IOException) {
        false
    }

    private fun replaceMcpAccess(): String = try {
        mcpAccess.managementReplaced()
        MCP_REPLACED
    } catch (e: IOException) {
        SafeFailureText.render(e)
    } catch (_: UnsupportedOperationException) {
        "MCP state requires owner-only filesystem permissions"
    }
}

// The answer's words, as `splice rotate-key` reads them.
private const val HEADER_WRITTEN = "written"
private const val MCP_REPLACED = "replaced"
