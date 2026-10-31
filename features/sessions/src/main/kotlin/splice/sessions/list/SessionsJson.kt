// NEW: Oct 27, 2026: `splice sessions --json`, the same registry `splice sessions` lists, for a program to read. The
// Temple lists the sessions it can message from here instead of keeping its own reader of Claude Code's registry. One
// document on one line: every readable registration with its identity (session id, name, pid and kernel start, socket),
// its state as Claude Code wrote it, and how Splice judged it (live, stale or gone, and the head it talks to). Registry
// text is passed through as written, since JSON carries it safely; a program decides what to show.
package splice.sessions.list

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonWire
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource

/** `splice sessions --json`: [output] takes the one document. */
public class SessionsJson(private val output: TerminalOutput) {

    /** Prints every registration [registry] reads; false when the registry could not be listed, which `error` says. */
    public fun print(registry: SessionSource): Boolean {
        val listing = registry.list()
        val document = buildJsonObject {
            put("sessions", buildJsonArray { listing.sessions.forEach { add(row(it)) } })
            put("error", listing.error)
        }
        output.line(JsonWire.string(document))
        return listing.error == null
    }

    private fun row(session: SessionRecord): JsonObject = buildJsonObject {
        val process = session.process
        put("sessionId", session.sessionId)
        put("name", session.name)
        put("pid", process.pid)
        put("procStart", process.procStart)
        put("cwd", process.cwd)
        put("startedAt", process.startedAt)
        put("updatedAt", process.updatedAt)
        put("status", session.status.state)
        put("statusUpdatedAt", session.status.updatedAt)
        put("waitingFor", session.status.waitingFor)
        put("availability", session.availability.name.lowercase())
        put("route", routeOf(session.route))
        put("head", session.head)
        put("address", session.address)
        put("socket", process.messagingSocketPath)
        put("kind", session.client.kind)
        put("version", session.client.version)
        put("peerProtocol", session.client.peerProtocol)
        put("registryDir", process.registryDir)
    }

    private fun routeOf(route: SessionRoute): String = when (route) {
        is SessionRoute.Head -> "head"
        SessionRoute.Direct -> "direct"
        SessionRoute.Unknown -> "unknown"
    }
}
