// NEW: LAN share, phase 2, the round-5 review (docs/specs/lan-share.md, Bounds). The machines this one joined as the
// Sharing page draws them: splice.toml's `[remotes]` as it is now, read at each call. A machine imported from the page
// has its card, and its Sessions switch, before splice restarts and starts its link; a token kept for a name the file
// no longer lists is no machine this one joined.
package splice.app.daemon

import splice.core.util.Cancellables
import splice.share.MachinesJoined
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** The `[remotes]` of the splice.toml at [file]; [booted], the ones the daemon started with, while it does not load,
 *  as the Sharing page then draws no card and says why. */
internal class RemotesInFile(private val file: Path, private val booted: Set<String>) : MachinesJoined {
    override fun names(): Set<String> =
        Cancellables.runCatchingCancellable { TopologyLoader.parse(Files.readString(file)).remotes.keys }
            .getOrElse { booted }
}
