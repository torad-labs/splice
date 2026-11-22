// NEW: head-catalog (gw-check). `splice claude check [--json]`: how Claude Code starts on this machine and what list
// each start gets, so a person or a script can see that `claude` on the Splice gateway is the one way and the
// gateway's list the one list. It reads only: the daemon's word on how each command launches now (GET
// /api/claude/heads, which launches nothing), and the files (ClaudeStarts). Exit 0 when one way and one list hold.
package splice.app.cli.claude

import splice.app.cli.desktop.DoorClient
import splice.app.cli.desktop.UNREADABLE
import splice.core.config.InstallPaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.launch.install.InstallLayout

internal class ClaudeCheck(
    private val client: DoorClient,
    private val env: EnvReader,
    private val out: TerminalOutput,
) {
    fun run(rest: List<String>): Int {
        val json = rest == listOf("--json")
        if (rest.isNotEmpty() && !json) return client.refused("usage: splice claude check [--json]")
        return checked(json)
    }

    private fun checked(json: Boolean): Int {
        val reply = client.read("heads") ?: return 1
        val facts = client.parsed(reply) ?: return client.refused(UNREADABLE)
        val bin = InstallPaths(envReader = env).binDir
        val launchers = ClaudeLaunchers(env, bin, InstallLayout().launchShimPath(env))
        val found = ClaudeStartsRead(launchers, bin, UserHome.dir(env), System.getProperty("os.name").orEmpty())
            .read(facts)
        if (json) out.line(found.json().toString()) else found.lines().forEach { out.line("splice: $it") }
        return if (found.oneWay && found.oneList) 0 else 1
    }
}
