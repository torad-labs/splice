// NEW: what an install that predates this release has not set up yet, from what CapabilityLook read: the "What's new /
// action needed" list an interactive `splice upgrade` prints after a new release passes its checks, and the offers
// `splice setup --update` makes. Each gap is one line, in the words of what to do about it; a machine without the
// tool a gap is about has no gap. Nothing here changes anything.
package splice.app.capability

import splice.app.desktop.setup.DesktopMode
import splice.app.seatd.SeatdAdvice
import splice.core.mcp.McpHarness

/** What a gap is about, so `splice setup --update` can offer the step that closes it. */
internal enum class GapKind {
    DESKTOP_STANDARD,
    MANAGED_SETTINGS,
    SIDE_BY_SIDE,
    MCP_SYNC,
    CODEX_HOOKS,
    GEMINI_SHIM,
    HUB_SYSTEMD,
    AUTO_UPDATE,
    SEATD,
}

/** One gap: what it is about and the line a person reads. */
internal data class Gap(val kind: GapKind, val text: String)

/** [claudeWrapped]: plain `claude` runs through splice's launch shim (an older setup wrapped it). */
internal class CapabilityGaps(private val facts: Capabilities, private val claudeWrapped: Boolean) {
    /** Every gap, `[daemon] auto_update` included. */
    fun all(): List<Gap> =
        listOfNotNull(desktop(), managed(), sideBySide(), codexHooks(), shim(), hub()) + sync() + autoUpdate()

    /** The gaps an upgrade says: all but auto_update, which is a choice, not something the release left undone. */
    fun afterUpgrade(): List<Gap> = all().filterNot { it.kind == GapKind.AUTO_UPDATE } + listOfNotNull(seatd())

    private fun seatd(): Gap? = Gap(
        GapKind.SEATD,
        "seatd-pipe is not installed. ${SeatdAdvice.about} ${SeatdAdvice.how}",
    ).takeIf { facts.seatd == null }

    private fun desktop(): Gap? = Gap(
        GapKind.DESKTOP_STANDARD,
        "Claude Desktop is on the older Code-tab-only door; `splice desktop on` puts all of Desktop on splice",
    ).takeIf { facts.desktop.mode == DesktopMode.STANDARD }

    /** Claude Code's managed settings, an item wherever the files splice read, merged, do not give both keys
     *  as wanted, or the launcher is not laid: what was found, the places it did not read, and the file to edit. Only
     *  root writes those files, so the item says what to do. */
    private fun managed(): Gap? {
        val managed = facts.desktop.managed?.takeIf { facts.desktop.mode == DesktopMode.APP } ?: return null
        if (managed.right && managed.laid) return null
        val step = managed.pointed()?.let { "what to do: $it" }
        return Gap(GapKind.MANAGED_SETTINGS, listOfNotNull(managed.said(), step).joinToString("; "))
    }

    /** Side-by-side launchers, where nothing runs Claude Desktop or Claude Code beside the person's own yet. */
    private fun sideBySide(): Gap? {
        val d = facts.desktop
        val none = !d.sideBySideCli && !d.sideBySideApp
        return when {
            !none -> null
            claudeWrapped -> Gap(
                GapKind.SIDE_BY_SIDE,
                "plain `claude` is wrapped by splice; the side-by-side `claude-splice` launcher runs splice beside " +
                    "your own `claude` instead",
            )
            d.program != null && d.mode == DesktopMode.OFF -> Gap(
                GapKind.SIDE_BY_SIDE,
                "Claude Desktop is installed: the Claude (Splice) launcher runs it on splice beside your own",
            )
            else -> null
        }
    }

    private fun codexHooks(): Gap? {
        if (facts.harness.codex == null) return null
        val reading = facts.harness.codexHooks ?: return null
        return when (reading.state) {
            CodexHooksState.NEEDS_MIGRATION -> Gap(
                GapKind.CODEX_HOOKS,
                "${reading.file} needs peer hook migration (${reading.issues.joinToString("; ")})",
            )
            // Missing ~/.codex/hooks.json is an opt-in doctor suggestion, not a required upgrade gap.
            CodexHooksState.MISSING, CodexHooksState.CURRENT, CodexHooksState.UNREADABLE -> null
        }
    }

    private fun sync(): List<Gap> = listOfNotNull(
        syncGap(McpHarness.AGY, facts.harness.agy != null),
        syncGap(McpHarness.CODEX, facts.harness.codex != null),
    )

    private fun syncGap(harness: McpHarness, installed: Boolean): Gap? = Gap(
        GapKind.MCP_SYNC,
        "${harness.displayName} is installed but not synced with Splice MCP",
    ).takeIf { installed && harness !in facts.harness.synced }

    private fun shim(): Gap? = Gap(
        GapKind.GEMINI_SHIM,
        "the Antigravity shim Claude Desktop runs for agy's models is missing; `$INSTALL_ALL` lays it",
    ).takeIf { facts.harness.agy != null && !facts.harness.geminiShim }

    private fun hub(): Gap? = Gap(
        GapKind.HUB_SYSTEMD,
        "the MCP hub is on but systemctl or systemd-run is not on the PATH; the hub runs under systemd --user",
    ).takeIf { facts.hub.on && !facts.hub.systemd }

    private fun autoUpdate(): List<Gap> = listOfNotNull(
        Gap(GapKind.AUTO_UPDATE, "automatic updates are off ([daemon] auto_update)")
            .takeIf { facts.autoUpdate == null },
    )
}
