package splice.app.cli.mcp

import splice.app.cli.AdminSupport
import splice.client.mcp.TempleMcpEntry
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.wire.HttpStatus
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import splice.topology.TopologyStatePaths

internal const val MCP_SERVICE_USAGE =
    "install temple (--script FILE [--command COMMAND] | --launch-file FILE) --url URL [--codex-home DIR]"

// why: the refresh call is bounded after the Temple registration has already been persisted.
private const val SERVICE_REFRESH_MS = 90_000

/** Persists the already-resolved Temple entry, rather than teaching Splice a machine-specific checkout path.
 *  Temple calls this once while provisioning; the registration survives both processes and every reboot. */
internal class McpServiceCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
) {
    private val requestParser = McpServiceRequestParser()
    private val registration = McpServiceRegistration(env)

    fun run(args: List<String>): Int {
        val parsed = requestParser.parse(args)
        val wanted = parsed.request
        if (wanted == null) {
            err.line(parsed.error ?: "usage: splice mcp $MCP_SERVICE_USAGE")
            return 2
        }
        return try {
            val home = AdminSupport.home()
            val source = TempleMcpEntry(home, env, TopologyStatePaths(env, home).current())
            registration.persist(source, wanted)
            refresh()
        } catch (failure: java.io.IOException) {
            err.line("Temple MCP was not registered (${failure::class.simpleName}); no credential values were printed.")
            1
        }
    }

    private fun refresh(): Int {
        val key = (AdminSupport.readMgmtKey(env) as? MgmtKeyRead.Present)?.key
        val read = key?.let {
            val url = "http://127.0.0.1:${AdminSupport.controlPort(env)}/api/mcp/services/refresh"
            ControlPlaneClient.post(url, it, "{}", SERVICE_REFRESH_MS)
        }
        return when {
            read == null || read == PostReading.NotConnected -> {
                out.line("Temple MCP registered durably. Splice will start it at its next daemon boot.")
                0
            }
            read is PostReading.Answered && read.reply.status == HttpStatus.OK -> {
                out.line("Temple MCP registered and its client entries refreshed through Splice.")
                0
            }
            else -> {
                err.line("Temple MCP is registered, but the live refresh did not complete. Retry the same install.")
                1
            }
        }
    }
}
