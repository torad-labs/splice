// NEW: how a registered Claude Code session reaches its provider, decided ONCE where the process
// environment is read (ProcessEnvironment.route) and carried unchanged through the registry's
// SessionRecord to every surface. Before it, a null head meant two different facts: a session that
// never went through splice (its environment was read and carries no SPLICE=1), and one splice cannot
// place (the environment was unreadable, or SPLICE=1 names a port no head owns). /api/sessions printed
// both as "unknown head", so a direct session read as a routing fault. door-sessions (Nov 20, 2026): a session
// launched on a door, this machine's or another machine's, is a fourth place, read from its launch record with the
// same reader the record was written with (LaunchDoors), so a record and a row can never disagree.
package splice.sessions.registry

import splice.core.topology.LaunchDoor

/** Where one session's turns go, as far as its launch record and process environment say. */
public sealed class SessionRoute {
    /** Launched by splice against the head whose key is [key]. */
    public data class Head(val key: String) : SessionRoute()

    /** Launched by splice on a [door]: this machine's (its machine is null) or another machine's. Its turns run on
     *  whichever head the model they name is on, so no head is its own. */
    public data class Door(val door: LaunchDoor) : SessionRoute()

    /** The environment was read and carries no SPLICE=1: the session talks to its provider directly. */
    public data object Direct : SessionRoute()

    /** Not placeable: the environment could not be read (another user's process, a gone or reused
     *  pid, a kernel that returned nothing), or SPLICE=1 names no local port a head owns and no door. */
    public data object Unknown : SessionRoute()
}

/** The key of the head whose client wrapper listens on a local port, or null when no head does. The
 *  topology is the caller's; [ProcessEnvironment.route] only asks it about the port it read. */
public fun interface HeadOfPort {
    public operator fun invoke(port: Int): String?
}
