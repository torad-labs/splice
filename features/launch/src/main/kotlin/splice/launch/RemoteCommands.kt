// NEW: LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24). A command this machine runs as Claude Code
// pointed at another machine's head, the way Claude Code runs on Vertex or Bedrock: the launch asks that machine for
// the head's models and builds the recipe a head here gets, with that machine's address as its base URL and no
// credential. The share module answers it; the launch routes ask it for a name no head here carries.
package splice.launch

import java.nio.file.Path

/** The commands another machine serves, read at launch time. */
public interface RemoteCommands {
    /** The launch [command] names on another machine, or null when no remote command is called that. */
    public suspend fun launch(command: String): RemoteLaunch?

    /** The config tree [command] keeps on this machine, where its sessions are recorded as its own so a bare -c
     *  continues them, or null when no remote command is called that. */
    public fun ownTree(command: String): Path?
}

/** What a remote command's launch came to before Claude Code starts. */
public sealed class RemoteLaunch {
    /** The spec to launch from, built from what the other machine answered. */
    public data class Ready(val spec: LaunchSpec) : RemoteLaunch()

    /** The other machine could not be reached or refused: [reason] says which, in words the person can act on. */
    public data class Refused(val reason: String) : RemoteLaunch()
}
