// NEW: who is launching. The calling shim's working directory, whether it came through the wrapped default `claude`,
// and its own config root are facts of one request and travel together; LaunchService reads each of them from here.
package splice.launch.recipe

import splice.client.wrap.WrappedLaunch
import java.nio.file.Path
import java.nio.file.Paths

public data class LaunchCaller(
    /** V4-183: the shim's working directory; null from a shim older than shim-4, which leaves -c unbounded. */
    val cwd: String? = null,
    /** V4-129 review: non-null when this launch came THROUGH the wrapped default `claude` command
     *  ([WrappedHead.launchThrough]): its settings ride an overlay, never a write into vanilla. */
    val wrapped: WrappedLaunch? = null,
    /** The calling shim's config root, not the daemon's own inherited environment. */
    val inheritedConfigDir: String? = null,
    /** MCP hub step 6: the launcher's own pid, which exec keeps for Claude Code; null from a shim before shim-20. */
    val launch: String? = null,
    /** head-catalog: the Splice gateway a head's command starts its session on, in Claude Code's gateway mode; null
     *  for a head that forwards the person's own Claude login, a command another machine serves, and a daemon with no
     *  gateway open. */
    val gateway: GatewayAt? = null,
    /** The command the person typed, which the launch's notice names (head-catalog); null where none is known. */
    val command: String? = null,
    val ambient: LaunchCallerAmbient = LaunchCallerAmbient(),
) {
    /** The cwd as an absolute path, or null when the shim sent none or a relative one. */
    internal fun absoluteCwd(): Path? = cwd?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
}

/** The caller shell's explicit launch inputs; the daemon never reads the rest of its environment. */
public data class LaunchCallerAmbient(
    val noProxy: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val modsOff: String? = null,
    val noHold: Boolean? = null,
)
