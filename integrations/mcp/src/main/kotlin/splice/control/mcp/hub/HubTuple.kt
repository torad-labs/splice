// NEW: MCP hub steps 3 and 4 — what one holder runs: the command, arguments, environment and folder of a server as
// the hub starts it. A shared server runs its declared tuple in the home folder with the daemon's environment, as the
// hosted copy always has. A per-session copy runs as Claude Code itself would have started it for that session: in
// the session's folder (or the entry's own cwd, resolved against it), with the session's whole environment under
// the entry's, and every `${VAR}` and `${VAR:-default}` expanded from the session's environment. A variable the
// session does not set and that names no default is left as written, which is what Claude Code does too.
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.mcp.McpServerSpec
import java.nio.file.Path
import java.security.MessageDigest

private val VARIABLE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}""")

/** One holder's server, ready to start; [inheritEnv] true layers [env] over the daemon's own environment. */
internal data class HubLaunchSpec(
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
    val folder: Path,
    val inheritEnv: Boolean,
)

internal class HubTuple(private val home: Path) {
    /** [spec] shared: its declared tuple, in the home folder, over the daemon's environment. */
    fun shared(spec: McpServerSpec): HubLaunchSpec = HubLaunchSpec(spec.command, spec.args, spec.env, home, true)

    /** [spec] as Claude Code would have started it for the session at [place]. */
    fun perSession(spec: McpServerSpec, place: McpLaunchPlace): HubLaunchSpec {
        val env = place.env.read()
        val expand = { value: String -> expanded(value, env) }
        val folder = spec.cwd?.let { place.folder.resolve(expand(it)).normalize() } ?: place.folder
        return HubLaunchSpec(
            expand(spec.command),
            spec.args.map(expand),
            env + spec.env.mapValues { expand(it.value) },
            folder,
            false,
        )
    }

    /** The SHA-256 of [spec]'s declared command, arguments and environment, plus [launch] for a per-session copy:
     *  no value (often a key) is ever in a file or socket name. */
    fun digest(spec: McpServerSpec, launch: String? = null): String {
        val parts = listOfNotNull(
            JsonPrimitive(spec.command),
            JsonArray(spec.args.map(::JsonPrimitive)),
            JsonObject(spec.env.toSortedMap().mapValues { JsonPrimitive(it.value) }),
            launch?.let(::JsonPrimitive),
        )
        return MessageDigest.getInstance("SHA-256").digest(JsonArray(parts).toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun expanded(value: String, env: Map<String, String>): String =
        VARIABLE.replace(value) { m -> env[m.groupValues[1]] ?: m.groups[2]?.value ?: m.value }
}
