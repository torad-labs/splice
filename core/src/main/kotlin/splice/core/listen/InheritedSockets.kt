// NEW: what a socket manager handed the daemon, read as a plan. The protocol is sd_listen_fds: LISTEN_PID names the
// process the descriptors were meant for, LISTEN_FDS counts them from descriptor 3, and LISTEN_FDNAMES names each one.
// Splice matches by NAME, never by position, so a manager that reorders its sockets cannot hand the control port to a
// head. This file is pure: it never opens a descriptor. Whether a named descriptor really is a listening loopback
// socket on the right port is checked where the descriptor is touched, in integrations/http.
package splice.core.listen

import splice.core.util.EnvReader

/** The first descriptor a socket manager passes (sd_listen_fds' SD_LISTEN_FDS_START). */
internal const val LISTEN_FDS_START: Int = 3

/** The three variables of the protocol. */
internal const val LISTEN_PID: String = "LISTEN_PID"
internal const val LISTEN_FDS: String = "LISTEN_FDS"
internal const val LISTEN_FDNAMES: String = "LISTEN_FDNAMES"

/** The names a socket manager passes the listeners under, and `splice listeners` prints: one source for both. */
public class ListenerNames {
    public val control: String = "control"

    public fun head(key: String): String = "head-$key"
}

/** What the daemon does about the descriptors it was started with. */
public sealed class InheritedPlan {
    /** Nothing was passed to this process: every listener binds its own port, exactly as before. */
    public data object None : InheritedPlan()

    /** [fds] maps a listener name to its descriptor; a head absent from it binds its own port. */
    public data class Adopt(val fds: Map<String, Int>) : InheritedPlan()

    /** The manager's hand-off cannot be trusted; the daemon refuses to boot and lists every reason at once. */
    public data class Refuse(val reasons: List<String>) : InheritedPlan()
}

public class InheritedSockets(private val pid: Long) {
    private val names = ListenerNames()

    /**
     * [env] is LISTEN_PID / LISTEN_FDS / LISTEN_FDNAMES as the process saw them; [expected] is every listener name the
     * boot parse produced (control first). A hand-off that names something not in [expected], names one twice, or
     * leaves control out is refused; a missing head simply binds itself.
     */
    public fun plan(env: EnvReader, expected: Set<String>): InheritedPlan {
        val count = env(LISTEN_FDS)?.trim().orEmpty()
        // Nothing was passed, the hand-off is addressed elsewhere, or it is empty: every listener binds its own port.
        if (!addressedToThisProcess(env) || count == "0") return InheritedPlan.None
        val total = count.toIntOrNull()?.takeIf { it >= 0 }
            ?: return InheritedPlan.Refuse(listOf("malformed: LISTEN_FDS is '$count'"))
        return matched(env(LISTEN_FDNAMES), total, expected)
    }

    /** Descriptors addressed to another process (a launcher that exec'd through us) are not ours to touch. */
    private fun addressedToThisProcess(env: EnvReader): Boolean = env(LISTEN_PID)?.trim() == pid.toString()

    /** The names against the count and against what the boot parse expects, every defect listed at once. */
    private fun matched(passed: String?, total: Int, expected: Set<String>): InheritedPlan {
        val handed = passed?.split(':')
            ?: return InheritedPlan.Refuse(
                listOf("malformed: LISTEN_FDNAMES is missing, so descriptors cannot be matched"),
            )
        if (handed.size != total) {
            return InheritedPlan.Refuse(
                listOf("malformed: LISTEN_FDS is $total but LISTEN_FDNAMES names ${handed.size}"),
            )
        }
        val reasons = mutableListOf<String>()
        val fds = LinkedHashMap<String, Int>()
        handed.forEachIndexed { index, name ->
            when {
                name in fds -> reasons += "duplicate: '$name' is passed twice"
                name !in expected -> reasons += "extra: '$name' is not a splice listener"
                else -> fds[name] = LISTEN_FDS_START + index
            }
        }
        if (names.control !in fds) reasons += "missing: control is not passed, so the daemon cannot be reached"
        return if (reasons.isEmpty()) InheritedPlan.Adopt(fds) else InheritedPlan.Refuse(reasons)
    }
}
