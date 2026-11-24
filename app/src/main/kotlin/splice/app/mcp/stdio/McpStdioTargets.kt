// NEW: desktop-3p-parity (Nov 23, 2026). Where the stdio MCP bridge (`splice mcp stdio <name>`) finds the server it
// speaks to, read when it starts from the same servers and the same plan every other harness is given: Splice's own
// `/mcp/<name>` with the MCP access key for a server Splice hosts, or the URL and headers the person gave a server of
// theirs. Desktop's configuration names only the server, so no address, key or header is ever written there, and a
// change to the server reaches Desktop at its next start.
package splice.app.mcp.stdio

import kotlinx.serialization.json.JsonObject
import splice.app.mcp.McpHarnessSharing
import splice.app.mcp.McpHarnessSyncSeams
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/** Where the bridge's server is, or why there is none to bridge. */
internal sealed class McpStdioResolution {
    data class Found(val target: McpStdioTarget) : McpStdioResolution()

    data class Missing(val why: String) : McpStdioResolution()
}

internal class McpStdioTargets(
    private val home: Path,
    private val paths: StatePaths,
    private val env: EnvReader,
    private val log: LogSink,
    private val seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
) {
    /** Where the active server [name] is reached. */
    fun resolve(name: String): McpStdioResolution = try {
        val sharing = McpHarnessSharing(paths, env, log, seams)
        val inputs = sharing.inputs(home)
        if (inputs.none { it.name == name }) {
            McpStdioResolution.Missing("no MCP server named '$name' is active in Splice")
        } else {
            val plan = sharing.planner(sharing.endpointPrefix()).plan(inputs, McpHarness.CLAUDE.wire)
            val entry = plan.rewritten[name] as? JsonObject
            target(name, entry) ?: McpStdioResolution.Missing(
                "'$name' is not reached at a URL (${plan.passthrough[name] ?: "a stdio server"}); " +
                    "Claude Desktop starts it itself",
            )
        }
    } catch (failure: IOException) {
        McpStdioResolution.Missing("Splice's MCP servers could not be read (${SafeFailureText.render(failure)})")
    }

    /** [entry]'s address, when it is a web one; refused, without saying any header's value, when one of its headers
     *  could not be sent (a name that is no HTTP token, or a value holding a line break or a control character). */
    private fun target(name: String, entry: JsonObject?): McpStdioResolution? {
        val uri = JsonScalars.str(entry, "url")?.let(::expanded)?.let(::web) ?: return null
        val headers = (entry?.get("headers") as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            JsonScalars.str(value)?.let { key to expanded(it) }
        }.toMap()
        val sendable = headers.all { (key, value) -> HEADER_NAME.matches(key) && value.none(::unsendable) }
        return if (sendable) {
            McpStdioResolution.Found(McpStdioTarget(uri, headers, JsonScalars.str(entry, "type") == "sse"))
        } else {
            McpStdioResolution.Missing("a header '$name' is given holds a line break or is no HTTP header name")
        }
    }

    /** Whether [char] can't go in a header value: a control character other than tab. */
    private fun unsendable(char: Char): Boolean = char != '\t' && (char < ' ' || char == '\u007f')

    /** [url] as an http or https address with a host, or null. */
    private fun web(url: String): URI? = try {
        URI(url).takeIf { it.scheme?.lowercase() in WEB_SCHEMES && !it.host.isNullOrBlank() }
    } catch (_: URISyntaxException) {
        null
    }

    /** [value] with each `${VAR}` and `${VAR:-default}` replaced from the environment, as Claude Code reads it. */
    private fun expanded(value: String): String = VARIABLE.replace(value) { match ->
        env(match.groupValues[1])?.takeIf { it.isNotEmpty() } ?: match.groupValues[DEFAULT_GROUP]
    }
}

private val WEB_SCHEMES = setOf("http", "https")
private val VARIABLE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}""")

// An HTTP header name: one RFC 9110 token.
private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

// The regex group holding a variable's `:-default`, empty when there is none.
private const val DEFAULT_GROUP = 2
