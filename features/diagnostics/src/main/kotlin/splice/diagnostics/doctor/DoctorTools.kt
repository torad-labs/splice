// NEW: the doctor's "tools" section — the optional programs splice works beside (Claude Desktop, Antigravity's agy,
// Codex, systemd for the MCP hub) and what splice laid for them (the Antigravity shim, the managed processWrapper).
// The executable reads them (it holds Desktop's mode and the MCP hub's state) and hands the doctor the readings, as it
// does the lane (ClaudeLaneRead). Every row is OK, INFO or WARN, never FAIL: a machine that does not use a tool is not
// a broken install, so nothing here can turn the exit status red.
package splice.diagnostics.doctor

import splice.core.topology.Topology
import splice.core.util.EnvReader

/** How a tool reads: fine, worth knowing, or something the person may want to fix. Never a failure. */
public enum class ToolLevel { OK, INFO, WARN }

/** One tool in words: its [name] (the row's label), its [level], [said] what was found, and [fix] what to run. */
public data class ToolReading(val name: String, val level: ToolLevel, val said: String, val fix: String? = null)

/** The tools of [Topology]'s machine (null when splice.toml does not parse), read under [EnvReader]. */
public fun interface ToolsRead {
    public operator fun invoke(topology: Topology?, env: EnvReader): List<ToolReading>
}

/** The tools section: a row per reading [read] answers. */
internal class DoctorToolChecks(private val read: ToolsRead) {
    fun checks(topology: Topology?, env: EnvReader): List<DoctorCheck> = read(topology, env).map {
        DoctorCheck(it.name, status(it.level), it.said, it.fix)
    }

    private fun status(level: ToolLevel): CheckStatus = when (level) {
        ToolLevel.OK -> CheckStatus.OK
        ToolLevel.INFO -> CheckStatus.INFO
        ToolLevel.WARN -> CheckStatus.WARN
    }
}

/** The section's title in the report and its JSON ids. */
internal const val TOOLS_SECTION = "tools"
