// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): the Codex process an attach binds its thread to. It is
// read from the attach's own ancestry, never from an argument, an event or the environment: the nearest process above
// it whose program is `codex`, as Fence's lifecycle binding names a native owner (Fence's lifecycle-owner-binding.md).
// A hook Codex runs has that process above it; a shell outside Codex has none, and its attach binds no owner.
package splice.sessions.peer.codex

import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.registry.PidIdentity
import splice.sessions.registry.ProcPidIdentity
import java.nio.file.Path

/** Finds the Codex process above this one. */
public class CodexOwners(
    identity: PidIdentity = ProcPidIdentity(),
    proc: Path = Path.of("/proc"),
) {
    private val owners = PeerOwnerProcesses(identity, proc)

    /** The nearest ancestor running `codex`, named as the registry names a process; null when there is none. */
    public fun find(): NativeProcess? = owners.ancestor(setOf("codex"))
}
