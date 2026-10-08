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
) {
    /** The cwd as an absolute path, or null when the shim sent none or a relative one. */
    internal fun absoluteCwd(): Path? = cwd?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
}
