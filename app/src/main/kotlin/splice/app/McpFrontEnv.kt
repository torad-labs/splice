// NEW: the MCP hub front's environment file (docs/specs/mcp-hub.md; hostshield-lead's installer contract, Oct 12).
// splice-mcp-front.service's ExecStart reads it, so `splice init` writes it before the front's socket is ever enabled.
package splice.app

import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** `mcp-front.env` beside splice.toml: where the hub keeps its state and which control port the front reports to.
 *  Owner-only and atomic. A value that changed is written for the front's next start and said as a config migration,
 *  because a running front keeps the values it started with, and restarting it would drop every session's MCP tools. */
internal class McpFrontEnv(private val file: Path) {
    /** What to tell the operator, or null when the file already says this. */
    fun write(hubDir: Path, controlPort: Int): String? {
        val wanted = "SPLICE_MCP_HUB=$hubDir\nSPLICE_MCP_CONTROL_PORT=$controlPort\n"
        val held = if (Files.exists(file)) Files.readString(file) else null
        if (held == wanted) return null
        SecureFile.writeAtomic0600(file, wanted)
        return if (held == null) {
            "splice: wrote the MCP hub front's settings to $file"
        } else {
            "splice: the MCP hub front's settings changed in $file, a config migration: a running front keeps " +
                "its old ones until splice-mcp-front.service restarts, which drops every session's MCP connection"
        }
    }
}
