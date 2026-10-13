// NEW: MCP hub steps 3 and 4, the daemon half (docs/specs/mcp-hub.md; splice-lead, Oct 12; shapes agreed with
// splice-config, who builds the front). The front names a call's session by the launch id in its hub address's
// X-Splice-Launch header and passes it here; these are the two questions the daemon asks about that launch: where it
// runs (the folder and environment a per-session copy starts with), and which servers it loads.
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/** Where one launch's Claude Code runs: its working folder, and its whole environment, which Claude Code itself would
 *  hand a stdio server it started and expand `${VAR}` from. The environment can hold credentials, so it is read only
 *  when a per-session copy starts, held for that spawn, and never logged or written. */
public data class McpLaunchPlace(val folder: Path, val env: McpLaunchEnv)

/** The launch's environment, read when asked; empty when it can no longer be read. */
public fun interface McpLaunchEnv {
    public fun read(): Map<String, String>
}

/** The place of a live launch, or null when the id is unknown, empty or its process is gone (the session ended). */
public fun interface McpLaunchPlaces {
    public fun of(launch: String): McpLaunchPlace?
}

/** The `mcpServers` object one launch loads, by the names Claude Code gives them; [place] null for an unknown
 *  launch. */
public fun interface McpLaunchServers {
    public fun servers(launch: String, place: McpLaunchPlace?): JsonObject
}
