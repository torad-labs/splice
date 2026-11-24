// NEW: what CapabilityLook reads of the optional things splice works beside, as plain values: the doctor's tools rows
// (CapabilityTools), the upgrade's and `splice setup --update`'s gaps (CapabilityGaps) and setup's report read these
// and nothing else, so each can be built by hand in a test.
package splice.app.capability

import splice.app.desktop.setup.DesktopMode
import splice.core.mcp.McpHarness
import java.nio.file.Path

/** Whether Claude Code's managed settings name a `processWrapper`, and whose: splice's `desktop-launch`, another
 *  program, or none (no managed file, or one without the key). */
internal enum class WrapperState { SPLICE, OTHER, NONE }

/** Claude Desktop: its [program] (null when not installed), the [mode] splice has it in, whether `claude-splice`
 *  ([sideBySideCli]) and the `Claude (Splice)` app launcher ([sideBySideApp]) are installed, and the managed
 *  [wrapper]. */
internal data class DesktopFacts(
    val program: Path?,
    val mode: DesktopMode,
    val sideBySideCli: Boolean,
    val sideBySideApp: Boolean,
    val wrapper: WrapperState,
)

/** The other harnesses: Antigravity's [agy] and [codex] on the PATH (null when absent), whether the Antigravity
 *  [geminiShim] is laid, and the harnesses whose MCP file already points at splice ([synced]). */
internal data class HarnessFacts(
    val agy: Path?,
    val codex: Path?,
    val geminiShim: Boolean,
    val synced: Set<McpHarness>,
)

/** The MCP hub: whether this is [linux], the hub's front is installed with sharing not turned off ([on]), and
 *  `systemctl` and `systemd-run` are both on the PATH ([systemd]), which the hub runs under. */
internal data class HubFacts(val linux: Boolean, val on: Boolean, val systemd: Boolean)

/** Everything [CapabilityLook] read, and `[daemon] auto_update` as splice.toml says it (null: the file never said). */
internal data class Capabilities(
    val desktop: DesktopFacts,
    val harness: HarnessFacts,
    val hub: HubFacts,
    val autoUpdate: Boolean?,
)
