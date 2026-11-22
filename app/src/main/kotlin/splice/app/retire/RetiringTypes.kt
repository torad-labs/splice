// NEW: head-catalog (gw-retire). What retiring reads of a head and of the lane `claude` is on, kept apart from
// HeadRetirement so the types it is built from and the work it does are read on their own.
package splice.app.retire

import java.nio.file.Path

/** One head as retiring reads it: its [key], the [command] a person types, its own Claude [folder] (null for the head
 *  `claude` runs on, which has none), the [selector] its pinned model runs as in `claude`, and whether its command
 *  starts on the Splice [gateway] (LaunchSpec.startsOnGateway). */
internal data class RetiringHead(
    val key: String,
    val command: String,
    val folder: Path?,
    val selector: String?,
    val gateway: Boolean,
)

/** The launchable heads, read at each ask. */
internal fun interface RetiringHeads {
    operator fun invoke(): List<RetiringHead>
}

/** The lane `claude` is on, as retiring the head `claude` runs on moves it. */
internal interface RetiringLane {
    /** The lane now (door or head), or null before the daemon has opened the Splice gateway. */
    fun lane(): String?

    /** `claude` put on the Splice gateway as `splice claude door` puts it, said. */
    suspend fun toGateway(): String

    /** `claude` put back on its head as `splice claude head` puts it, said. */
    suspend fun toHead(): String

    /** The selectors [key]'s models run as in `claude`'s list, as `splice claude --json` lists them. */
    suspend fun selectors(key: String): List<String>
}
