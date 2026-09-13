// NEW (v0.4.0, FEATURES.md §4): `/api/sessions` — the registry as JSON, read on every request
// (Claude Code rewrites the files as sessions come and go). Read-only: no socket is ever opened.
package splice.control.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.sessions.SessionRecord
import splice.core.sessions.SessionRegistry

internal const val UNKNOWN_HEAD = "unknown head"
internal const val HEADLESS_NOTE = "headless `claude -p` runs never register; gone = the process exited; " +
    "stale = alive but no registry update inside the stale window"

public class SessionsRoutes(private val registry: SessionRegistry) {
    public fun sessionsJson(): String = buildJsonObject {
        put("note", HEADLESS_NOTE)
        put("sessions", buildJsonArray { registry.read().forEach { add(row(it)) } })
    }.toString()

    private fun row(s: SessionRecord) = buildJsonObject {
        put("pid", s.pid)
        put("session_id", s.sessionId)
        put("name", s.name)
        put("kind", s.kind)
        put("version", s.version)
        put("cwd", s.cwd)
        put("status", s.status)
        put("status_updated_at", s.statusUpdatedAt)
        put("started_at", s.startedAt)
        put("updated_at", s.updatedAt)
        put("address", s.address)
        put("head", s.head ?: UNKNOWN_HEAD)
        put("availability", JsonPrimitive(s.availability.name.lowercase()))
    }
}
