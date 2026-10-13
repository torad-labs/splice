// NEW: the mark a splice launch leaves in its arguments on macOS (splice-lead, Oct 13). On Linux a session splice did
// not launch is told from a launch nothing recorded by its environment: SPLICE=1 is a splice launch. macOS hides the
// environment of a hardened binary that lacks the get-task-allow entitlement (Apple StackExchange 439943), which a
// notarized claude is likely to be, but its arguments stay readable (ps shows the command line there). So on macOS
// every launch hands Claude Code one more settings key, in the --settings JSON a wrapped launch already passes or,
// for a launch with none, in a --settings of its own; and the reader takes arguments read without it as DIRECT.
//
// Claude Code 2.1.296 ignores a settings key it does not know: run against a local stub API on Oct 13,
// --settings {"model":"claude-opus-4-8","spliceLaunch":true} still sent claude-opus-4-8, while an invalid known key
// ({"statusLine":5}) dropped the whole JSON and sent the default model.
//
// Off macOS nothing is added and nothing is read, so a Linux launch's arguments are byte-identical to before.
// UNTESTED ON A MAC: the reader takes ProcessHandle's arguments, which the JDK reads with sysctl KERN_PROCARGS2 there.
// A session launched by a splice from before this mark, with no launch record and a hidden environment, reads DIRECT.
package splice.sessions.registry

/** The settings key that marks a splice launch, and the arguments that carry it. The arguments carry the --settings
 *  JSON, which names a secret header file: they are matched here and never logged. */
public object LaunchMarker {
    /** The settings key every splice launch on macOS carries, true. */
    public const val SPLICE_LAUNCH_KEY: String = "spliceLaunch"

    /** Whether this host's launches carry the mark: macOS only. */
    public val here: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

    private const val SETTINGS_FLAG = "--settings"
    private val marked = "\"$SPLICE_LAUNCH_KEY\":true"

    /** The --settings arguments of a launch: [overlay], the JSON a wrapped launch passes (marked by its writer on
     *  macOS), or, for a launch with none, the mark alone on macOS and nothing elsewhere. */
    public fun settingsArgs(overlay: String?): List<String> = when {
        overlay != null -> listOf(SETTINGS_FLAG, overlay)
        here -> listOf(SETTINGS_FLAG, "{$marked}")
        else -> emptyList()
    }

    /** Whether [pid]'s arguments carry the mark in a --settings JSON, or null when they could not be read. */
    public fun carriedBy(pid: Long): Boolean? {
        val arguments = ProcessHandle.of(pid).flatMap { it.info().arguments() }.orElse(null) ?: return null
        return arguments.toList().zipWithNext().any { (flag, value) -> flag == SETTINGS_FLAG && marked in value }
    }
}
