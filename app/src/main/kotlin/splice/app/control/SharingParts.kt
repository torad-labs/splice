// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). What the Sharing page's rows read and turn: this
// machine's share listener, and the machines it joined. A port of ConsolePorts, assigned after the server is built.
package splice.app.control

import splice.core.util.EnvReader
import splice.share.ShareListener
import splice.share.remote.RemoteLinks

/** This machine's share listener, and the machines it joined (null where no daemon built them). [env] is the daemon's:
 *  where splice.toml and the state folder are, which the rows read and write. */
internal data class SharingParts(
    val listener: ShareListener,
    val remotes: RemoteLinks?,
    val env: EnvReader = EnvReader(System::getenv),
)
