// NEW: the MCP hub's three systemd units (Oct 12). Oct 25, 2026 (splice-lead, on Host Shield's handover): splice
// lays them itself on every `splice install --all` beside an installed hub front, next to the daemon's own unit, in
// the folder systemd keeps for a user's installed software (SpliceUnits). Host Shield vendors no splice since
// Marcos's ruling of that day. The bytes under resources/splice/lifecycle/hub are the units Host Shield laid, each
// under splice's marker line.
// Oct 13 (splice-lead): `splice setup` turns the hub on when its share answer is yes, but only over splice's own
// units: each one systemd loads from the file splice laid, holding splice's bytes, with no drop-in. A unit of the same
// name in ~/.config/systemd/user is someone's override, which setup leaves to its owner and names the command for.
// Oct 13 (splice-lead): `splice upgrade` reloads a running front onto the programs it swapped in, through the unit's
// own ExecReload (USR2, the in-place re-exec), never a restart; over a front unit anyone else placed it prints the
// reload for its owner (reloadFront).
package splice.lifecycle.hub

import splice.core.terminal.TerminalOutput
import splice.lifecycle.units.SpliceUnits

/** The MCP hub's units as splice lays them through [units], and what setup and upgrade do over them. */
public class HubUnits(private val units: SpliceUnits, private val output: TerminalOutput) {
    /** The hub's three units, unit name to splice's bytes for it. */
    public fun templates(): Map<String, ByteArray> = NAMES.mapNotNull { name ->
        javaClass.getResourceAsStream("$HUB_UNITS_RESOURCE_ROOT/$name")?.use { name to it.readBytes() }
    }.toMap()

    /** Enables and starts the front's socket when every hub unit is splice's own, and says what it did. Over a unit
     *  anyone else placed or changed it touches nothing and prints the command. With no hub unit at all, it says
     *  nothing. */
    public fun enable() {
        if (NAMES.none(units::present)) return
        if (!NAMES.all(::ours)) {
            output.line(
                "splice: the MCP hub's units here are not the ones splice laid, so setup leaves them as they are; " +
                    "to turn the hub on, run: $ENABLE_LINE",
            )
            return
        }
        if (units.systemctl(ENABLE) != null) {
            output.line("splice: turned the MCP hub on (enabled and started $FRONT_SOCKET_UNIT)")
        } else {
            output.line("splice: the MCP hub's socket did not start; run: $ENABLE_LINE")
        }
    }

    /** After `splice upgrade` swapped the hub's programs: a running front takes the new one by reload, never a
     *  restart, when the front service systemd loads is splice's own. Over any other front unit it touches nothing
     *  and prints the command for its owner. Where no front unit is loaded at all, it says nothing. */
    public fun reloadFront() {
        if (!ours(SERVICE)) {
            if (units.systemctl(LOAD_STATE + SERVICE) != LOAD_STATE_NOT_FOUND) {
                output.line(
                    "splice: the MCP hub's front unit here is not the one splice laid, so its owner reloads the " +
                        "front onto the new program: $RELOAD_LINE",
                )
            }
            return
        }
        if (units.systemctl(listOf("is-active", "--quiet", SERVICE)) == null) return
        output.line(
            if (units.systemctl(listOf("reload", SERVICE)) != null) {
                "splice: reloaded $SERVICE onto the new program"
            } else {
                "splice: $SERVICE did not reload, so it keeps its old program until it next starts; run: $RELOAD_LINE"
            },
        )
    }

    private fun ours(name: String): Boolean = templates()[name]?.let { units.ours(name, it) } == true
}

private val NAMES = listOf("app-mcp.slice", "splice-mcp-front.socket", "splice-mcp-front.service")
private const val HUB_UNITS_RESOURCE_ROOT = "/splice/lifecycle/hub"
private const val FRONT_SOCKET_UNIT = "splice-mcp-front.socket"
private const val SERVICE = "splice-mcp-front.service"
private val ENABLE = listOf("enable", "--now", FRONT_SOCKET_UNIT)
private const val ENABLE_LINE = "systemctl --user enable --now $FRONT_SOCKET_UNIT"
private val LOAD_STATE = listOf("show", "-p", "LoadState", "--value")
private const val LOAD_STATE_NOT_FOUND = "not-found"
private const val RELOAD_LINE = "systemctl --user reload $SERVICE"
