// NEW: desktop-3p-parity (Nov 23, 2026). `splice mcp stdio <name>`: the stdio MCP server Claude Desktop starts for an
// MCP server Splice hosts, or one the person reaches at a URL, since Desktop starts only stdio servers from its
// `claude_desktop_config.json`. stdout carries JSON-RPC alone; what the bridge has to say goes to stderr, which
// Desktop keeps in its MCP log, and never carries a header value or the MCP access key.
package splice.app.cli.mcp

import splice.app.cli.AdminSupport
import splice.app.mcp.McpHarnessSyncSeams
import splice.app.mcp.stdio.McpLegacySse
import splice.app.mcp.stdio.McpStdioBridge
import splice.app.mcp.stdio.McpStdioResolution
import splice.app.mcp.stdio.McpStdioTarget
import splice.app.mcp.stdio.McpStdioTargets
import splice.app.mcp.stdio.McpStreamableHttp
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.topology.TopologyStatePaths
import splice.upstream.codemode.ProcessDispatchers
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.http.HttpClient
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.time.Duration

/** The bridge's stdin and stdout: the process's own, or a test's. */
internal data class McpStdioPipes(val input: InputStream = System.`in`, val output: OutputStream = System.out)

internal class McpStdioCommand(
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
    private val homeOverride: Path? = null,
    private val seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
    private val pipes: McpStdioPipes = McpStdioPipes(),
) {
    suspend fun run(args: List<String>): Int {
        val name = args.singleOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("-") }
        if (name == null) {
            err.line("usage: splice mcp $MCP_STDIO_USAGE")
            return 2
        }
        val home = homeOverride ?: AdminSupport.home()
        val paths = TopologyStatePaths(env, home).current()
        val log = LogSink { err.line(it.trimEnd()) }
        return when (val found = McpStdioTargets(home, paths, env, log, seams).resolve(name)) {
            is McpStdioResolution.Missing -> {
                err.line("splice mcp stdio: ${found.why}")
                1
            }
            is McpStdioResolution.Found -> bridge(name, found.target)
        }
    }

    private suspend fun bridge(name: String, target: McpStdioTarget): Int {
        val io = ProcessDispatchers().io()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(STDIO_CONNECT_MS)).build()
        val transport = if (target.legacySse) {
            McpLegacySse(target, client, io)
        } else {
            McpStreamableHttp(target, client, io)
        }
        val input = InputStreamReader(pipes.input, Charsets.UTF_8.newDecoder()).buffered()
        val reached = try {
            McpStdioBridge(name, transport, io, err).run(input, pipes.output)
        } catch (_: CharacterCodingException) {
            err.line("splice mcp stdio: the input is not valid UTF-8 JSON")
            false
        }
        return if (reached) 0 else 1
    }
}

/** The verb's usage, after `splice mcp`. */
internal const val MCP_STDIO_USAGE: String = "stdio NAME"

// why: a loopback hub answers in milliseconds and a remote server within seconds; past ten the client is told the
// server can't be reached rather than waiting on a connection that will not come.
private const val STDIO_CONNECT_MS = 10_000L
