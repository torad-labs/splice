// NEW: LAN share, round 15 of PR #419's review. The machine an import writes a head for, out of RemoteImport once the
// key it is written under became the import's own choice (RemoteTopology.keyFor), not its name's.
package splice.app.cli.share

import splice.share.remote.RemoteJoined.Joined

/** The machine a head is imported from: its `[remotes]` [name], relay [url], own [server] name, the join, and the
 *  [host] it is reached at. */
internal data class RemoteTarget(
    val name: String,
    val url: String,
    val server: String,
    val joined: Joined,
    val host: String,
)
