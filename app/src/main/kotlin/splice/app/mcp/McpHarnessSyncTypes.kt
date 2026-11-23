// Outcome types and injectable seams for multi-harness MCP synchronization.
package splice.app.mcp

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.HUB_FRONT_CONNECT_MS
import splice.client.mcp.McpBearer
import splice.core.mcp.McpHarness
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

/** Outcome of syncing one harness to Splice MCP. */
internal data class HarnessSyncOutcome(
    val harness: McpHarness,
    val ok: Boolean,
    val changed: Boolean,
    val serverCount: Int,
    val hostedCount: Int,
    val detail: String,
)

/** Summary of a multi-harness sync pass. */
internal data class McpSyncReport(
    val serverCount: Int,
    val hostedCount: Int,
    val endpointPrefix: String,
    val outcomes: List<HarnessSyncOutcome>,
) {
    val allOk: Boolean get() = outcomes.all { it.ok }
}

/** Checks whether the MCP hub front state exists under [stateDir] and its loopback [port] answers. */
internal fun interface McpFrontCheck {
    fun live(stateDir: Path, port: Int): Boolean
}

/** Outcome of a Codex `app-server` RPC sync attempt. */
internal data class CodexRpcSyncResult(
    val ok: Boolean,
    val changed: Boolean = false,
    val error: String? = null,
)

/** Strategy for updating Codex via `codex app-server` RPC. */
internal fun interface CodexRpcSync {
    suspend operator fun invoke(codexHome: Path, entries: Map<String, JsonObject>): CodexRpcSyncResult
}

/** Injectable runtime seams for [McpHarnessSync], keeping constructor arity compact and tests hermetic. */
internal data class McpHarnessSyncSeams(
    val osName: String = System.getProperty("os.name").orEmpty(),
    val frontLive: McpFrontCheck = McpFrontCheck { stateDir, port ->
        val frontState = stateDir.resolve("mcp-hub").resolve("front.json")
        if (!Files.isRegularFile(frontState)) {
            false
        } else {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), HUB_FRONT_CONNECT_MS) }
                true
            } catch (_: IOException) {
                false
            }
        }
    },
    val codexRpc: CodexRpcSync? = null,
    val bearerOverride: McpBearer? = null,
)
