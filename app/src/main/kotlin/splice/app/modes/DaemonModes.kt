// NEW: the modes as the running daemon holds them (modes/README.md): the one catalog boot read, splice.toml's
// `[modes.<name>]` switches as they stand now (the switch updates them with its write, and the door reads them per
// request), the heads splice.toml declares, in its order, and each mode's store. An mcp mode whose server name is one
// of the person's own MCP servers yields the name to theirs (taken): it is not served at that address, not listed for
// a launch or for Claude Desktop, and its row says so, so their server is never replaced.
package splice.app.modes

import splice.core.mode.ModeSetting
import splice.modes.api.FoundMode
import splice.modes.api.ModeCaller
import splice.modes.api.ModeCatalog
import splice.modes.api.ModeKind
import splice.modes.api.ModeRule
import splice.modes.api.ModeState
import splice.modes.api.ModeStore
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal class DaemonModes(
    val catalog: ModeCatalog,
    settings: Map<String, ModeSetting>,
    /** Every head splice.toml declares, in its order. */
    val heads: List<String>,
    private val stateDir: Path,
    /** The names of the person's own MCP servers; none where null. */
    private val theirs: PersonMcpNames? = null,
) {
    private val switches = AtomicReference(settings)
    private val stores = ConcurrentHashMap<String, ModeStore>()

    /** splice.toml's switches as they stand now. */
    val settings: Map<String, ModeSetting> get() = switches.get()

    /** The switches after a write to splice.toml: the next request of any session follows them. */
    fun follow(settings: Map<String, ModeSetting>) {
        switches.set(settings)
    }

    fun mode(name: String): FoundMode? = catalog.mode(name)

    /** The mode's value for a session of [head] whose requests come from [caller] (ModeRule.forHead). */
    fun state(mode: FoundMode, head: String, caller: ModeCaller? = null): ModeState =
        ModeRule.forHead(mode.manifest, settings[mode.name], head, caller)

    /** The mode's every-head value for [caller] (ModeRule.everyHead). */
    fun everyHead(mode: FoundMode, caller: ModeCaller? = null): ModeState =
        ModeRule.everyHead(mode.manifest, settings[mode.name], caller)

    /** The door modes on for a session of [head] whose requests come from [caller], in name order. */
    fun offered(head: String, caller: ModeCaller): List<FoundMode> = catalog.modes.filter { mode ->
        mode.manifest.kind == ModeKind.DOOR && state(mode, head, caller).on
    }

    /** Every mode on for a session of [head] whose requests come from [caller], door or mcp: what may hook it. */
    fun hooksOn(head: String, caller: ModeCaller): List<FoundMode> = catalog.modes.filter { mode ->
        state(mode, head, caller).on && mode.hooks.isNotEmpty()
    }

    /** Whether [mode] is an mcp mode whose server name the person's own MCP server has, which keeps it. */
    fun taken(mode: FoundMode): Boolean =
        mode.manifest.kind == ModeKind.MCP && theirs?.names()?.contains(mode.manifest.server) == true

    fun store(mode: String): ModeStore = stores.computeIfAbsent(mode) { FileModeStore(stateDir, it) }
}
