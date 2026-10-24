package splice.app

import splice.app.sources.mcp.McpSessionRef
import splice.app.sources.mcp.McpSessionsByPid
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.sessions.activity.NameHolders
import splice.sessions.prompt.SessionAddress
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute

/** The session registry shared by console address, name-holder and MCP attribution adapters. */
internal object ConsoleSessionWiring {
    /** V4-131: a session id to its SendMessage address, from the same registry /api/sessions reads (the
     *  home the state dir lives under, as ControlPlane.start derives it), for the slot text's lead line. */
    internal fun sessionAddress(statePaths: StatePaths): SessionAddress {
        val registry = sessionRegistry(statePaths)
        return SessionAddress { session -> registry.read().firstOrNull { it.sessionId == session }?.address }
    }

    /** V4-252: a SendMessage name to the session holding it, over the same registry, asked as its edge
     *  is stored. */
    internal fun nameHolders(statePaths: StatePaths): NameHolders = NameHolders(sessionRegistry(statePaths))

    /** Oct 13: each live session by its Claude Code pid, over the same registry, for Settings › MCP to name the
     *  sessions holding each server. An ended session's pid is left out: it holds nothing. */
    internal fun sessionsByPid(statePaths: StatePaths): McpSessionsByPid {
        val registry = sessionRegistry(statePaths)
        return McpSessionsByPid {
            registry.read()
                .filter { it.availability != SessionAvailability.GONE }
                .mapNotNull { r -> r.process.pid?.let { pid -> r.sessionId?.let { pid to McpSessionRef(it, r.name) } } }
                .toMap()
        }
    }

    private fun sessionRegistry(statePaths: StatePaths): SessionRegistry {
        val home = statePaths.rootDir.parent ?: statePaths.rootDir
        return SessionRegistry(
            home.resolve(UserHome.CLAUDE_DIR).resolve("sessions"),
            RouteOfPid { SessionRoute.Unknown },
        )
    }
}
