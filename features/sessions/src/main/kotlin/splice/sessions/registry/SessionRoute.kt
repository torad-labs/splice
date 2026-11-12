// NEW: how a registered Claude Code session reaches its provider, decided ONCE where the process
// environment is read (ProcessEnvironment.route) and carried unchanged through the registry's
// SessionRecord to every surface. Before it, a null head meant two different facts: a session that
// never went through splice (its environment was read and carries no SPLICE=1), and one splice cannot
// place (the environment was unreadable, or SPLICE=1 names a port no head owns). /api/sessions printed
// both as "unknown head", so a direct session read as a routing fault.
package splice.sessions.registry

/** Where one session's turns go, as far as its process environment says. */
public sealed class SessionRoute {
    /** Launched by splice against the head whose key is [key]. */
    public data class Head(val key: String) : SessionRoute()

    /** The environment was read and carries no SPLICE=1: the session talks to its provider directly. */
    public data object Direct : SessionRoute()

    /** Not placeable: the environment could not be read (another user's process, a gone or reused
     *  pid, a kernel that returned nothing), or SPLICE=1 names no local port a head owns. */
    public data object Unknown : SessionRoute()
}

/** The key of the head whose client wrapper listens on a local port, or null when no head does. The
 *  topology is the caller's; [ProcessEnvironment.route] only asks it about the port it read. */
public fun interface HeadOfPort {
    public operator fun invoke(port: Int): String?
}
