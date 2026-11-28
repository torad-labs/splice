// NEW: the doctor's "tools" rows (DoctorTools), from what CapabilityLook read. A tool the machine does not use says
// nothing; one it uses says what it found, and nothing here is ever a failure: WARN is the most a missing optional
// program earns, so `splice doctor` stays green on a machine that runs none of them.
package splice.app.capability

import splice.app.desktop.setup.DesktopMode
import splice.app.seatd.SeatdAdvice
import splice.core.mcp.McpHarness
import splice.diagnostics.doctor.ToolLevel
import splice.diagnostics.doctor.ToolReading

internal class CapabilityTools(private val facts: Capabilities) {
    fun readings(): List<ToolReading> =
        listOfNotNull(desktop(), managed(), seatd(), agy(), shim(), codex(), codexHooks(), hub())

    private fun seatd(): ToolReading {
        val installed = facts.seatd
        if (installed != null) return ToolReading("seatd-pipe", ToolLevel.OK, "installed (${installed.version})")
        val level = if (facts.desktop.mode == DesktopMode.OFF) ToolLevel.INFO else ToolLevel.WARN
        return ToolReading("seatd-pipe", level, "not installed. ${SeatdAdvice.about}", SeatdAdvice.how)
    }

    /** Claude Desktop, said when splice has it on, or its Claude (Splice) app launcher is installed, or when it is
     *  installed at all. `claude-splice` runs Claude Code in a terminal, not Desktop, so it is no use of Desktop. */
    private fun desktop(): ToolReading? {
        val d = facts.desktop
        val uses = listOfNotNull(
            "`splice desktop on`".takeIf { d.mode == DesktopMode.APP || d.mode == DesktopMode.STANDARD },
            "the Claude (Splice) app launcher".takeIf { d.sideBySideApp },
        )
        val program = d.program
        return when {
            program == null && uses.isEmpty() -> null
            program == null -> ToolReading(
                DESKTOP_ROW,
                ToolLevel.WARN,
                "Claude Desktop is not installed, but splice has ${uses.joinToString(" and ")} set up for it",
                "install Claude Desktop, or turn splice's Desktop setup off with `splice desktop off`",
            )
            uses.isEmpty() -> ToolReading(
                DESKTOP_ROW,
                ToolLevel.INFO,
                "found ($program), not on splice; `splice launch claude-desktop` runs it beside your own",
            )
            else -> ToolReading(DESKTOP_ROW, ToolLevel.OK, "found ($program), used by ${uses.joinToString(" and ")}")
        }
    }

    /** Claude Code's managed settings for the Code tab, said while Desktop runs on splice in app mode: which file
     *  splice read holds each key, the places it could not read, and the file to edit (WrapperSettings). */
    private fun managed(): ToolReading? {
        val managed = facts.desktop.managed?.takeIf { facts.desktop.mode == DesktopMode.APP } ?: return null
        return ToolReading(MANAGED_ROW, managed.level(), managed.said(), managed.pointed())
    }

    private fun agy(): ToolReading? {
        val agy = facts.harness.agy ?: return null
        val synced = McpHarness.AGY in facts.harness.synced
        return ToolReading(
            AGY_ROW,
            if (synced) ToolLevel.OK else ToolLevel.INFO,
            "found ($agy); its sign-in is asked when a session opens (`agy acp --login` signs it in)" +
                if (synced) "; synced with Splice MCP" else "; not synced with Splice MCP",
            SETUP_UPDATE.takeUnless { synced },
        )
    }

    /** The shim Claude Desktop runs for an Antigravity model, said where agy is installed. */
    private fun shim(): ToolReading? {
        if (facts.harness.agy == null) return null
        return if (facts.harness.geminiShim) {
            ToolReading(SHIM_ROW, ToolLevel.OK, "laid")
        } else {
            ToolReading(
                SHIM_ROW,
                ToolLevel.WARN,
                "~/.local/lib/splice/gemini/claude is missing or stale; Claude Desktop can't run Antigravity models",
                INSTALL_ALL,
            )
        }
    }

    private fun codex(): ToolReading? {
        val codex = facts.harness.codex ?: return null
        val synced = McpHarness.CODEX in facts.harness.synced
        return ToolReading(
            CODEX_ROW,
            if (synced) ToolLevel.OK else ToolLevel.INFO,
            "found ($codex)" + if (synced) "; synced with Splice MCP" else "; not synced with Splice MCP",
            SETUP_UPDATE.takeUnless { synced },
        )
    }

    private fun codexHooks(): ToolReading? {
        if (facts.harness.codex == null) return null
        val reading = facts.harness.codexHooks ?: return null
        return when (reading.state) {
            CodexHooksState.CURRENT -> ToolReading(
                CODEX_HOOKS_ROW,
                ToolLevel.OK,
                "${reading.file} runs splice peer codex attach/detach directly",
            )
            CodexHooksState.MISSING -> ToolReading(
                CODEX_HOOKS_ROW,
                ToolLevel.INFO,
                "${reading.file} has no splice peer codex hooks yet",
                SETUP_UPDATE,
            )
            CodexHooksState.NEEDS_MIGRATION -> ToolReading(
                CODEX_HOOKS_ROW,
                ToolLevel.WARN,
                "${reading.file} needs migration (${reading.issues.joinToString("; ")})",
                SETUP_UPDATE,
            )
            CodexHooksState.UNREADABLE -> ToolReading(
                CODEX_HOOKS_ROW,
                ToolLevel.WARN,
                "${reading.file} could not be parsed; check it by hand",
            )
        }
    }

    /** systemd, said where the MCP hub is on: its front and holder run under `systemd --user`. */
    private fun hub(): ToolReading? {
        if (!facts.hub.on) return null
        return if (facts.hub.systemd) {
            ToolReading(SYSTEMD_ROW, ToolLevel.OK, "systemctl and systemd-run found for the MCP hub")
        } else {
            ToolReading(
                SYSTEMD_ROW,
                ToolLevel.WARN,
                "the MCP hub runs under systemd --user, but systemctl or systemd-run is not on the PATH",
                "install systemd's user tools, or turn sharing off with `mcp_share = false` under [daemon]",
            )
        }
    }
}

private const val DESKTOP_ROW = "claude-desktop"
private const val MANAGED_ROW = "managed settings"
private const val AGY_ROW = "agy"
private const val SHIM_ROW = "antigravity shim"
private const val CODEX_ROW = "codex"
private const val CODEX_HOOKS_ROW = "codex peer hooks"
private const val SYSTEMD_ROW = "systemd"

internal const val SETUP_UPDATE = "splice setup --update"
internal const val INSTALL_ALL = "splice install --all"
