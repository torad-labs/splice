// NEW: Oct 12, 2026 (V4-445, the President via splice-lead) — a plain `claude` reaches the servers splice hosts
// without a write to ~/.claude.json. Plain claude sessions each ran their own uvx ast-grep: 45 copies, 3.0 GiB.
package splice.client.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonObject
import splice.client.Keys
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The `--mcp-config` file a wrapped launch hands Claude Code: each of the operator's user-scope servers splice hosts,
 *  as the HTTP entry a head's home carries ([McpSharing.plan]). An entry passed this way replaces the user-scope one
 *  of the same name (Claude Code 2.1.296 on a desk, Oct 12: one ast-grep, "Dynamically configured", its stdio command
 *  never spawned), so plain claude connects to the one hosted copy instead of starting its own, and ~/.claude.json
 *  stays as he wrote it. The entries carry the MCP-scoped bearer, so they go in an owner-only file in [dir], never in
 *  argv, which any process on the machine can read. */
public class HostedMcpOverlay(
    private val home: Path,
    private val sharing: McpSharing,
    private val dir: Path,
    private val log: LogSink,
) {
    /** The launch's `--mcp-config <file>`, or nothing when [file] is null. */
    public fun args(headKey: String): List<String> =
        file(headKey)?.let { listOf("--mcp-config", it.toString()) }.orEmpty()

    /** The file for a launch of [headKey], or null when nothing is hosted or his file cannot be read: the launch then
     *  runs over his own servers as before. Rewritten per launch, so it follows his file and the bearer. */
    public fun file(headKey: String): Path? {
        val plan = sharing.plan(servers() ?: return null, headKey.ifBlank { null })
        if (plan.hosted.isEmpty()) return null
        val body = buildJsonObject {
            putJsonObject(Keys.MCP_SERVERS) {
                plan.hosted.keys.forEach { name -> plan.rewritten[name]?.let { put(name, it) } }
            }
        }
        val file = dir.resolve("wrapped-mcp-${headKey.ifBlank { "claude" }}.json")
        return try {
            SecureFile.writeAtomic0600(file, body.toString())
            file
        } catch (unwritable: IOException) {
            log("[mcp] plain claude runs its own servers: ${SafeFailureText.render(unwritable)}\n")
            null
        }
    }

    /** His user-scope `mcpServers`, as Claude Code reads them from ~/.claude.json while CLAUDE_CONFIG_DIR is unset. */
    private fun servers(): JsonObject? = try {
        Json.parseToJsonElement(Files.readString(home.resolve(Keys.CLAUDE_JSON))).jsonObject[Keys.MCP_SERVERS]
            as? JsonObject
    } catch (ignored: NoSuchFileException) {
        null
    } catch (unreadable: IOException) {
        log("[mcp] ~/.claude.json unreadable, plain claude runs its own: ${SafeFailureText.render(unreadable)}\n")
        null
    } catch (malformed: SerializationException) {
        log("[mcp] ~/.claude.json is not JSON, plain claude runs its own servers (${malformed::class.simpleName})\n")
        null
    } catch (malformed: IllegalArgumentException) {
        log("[mcp] ~/.claude.json is not an object, plain claude runs its own (${malformed::class.simpleName})\n")
        null
    }
}
