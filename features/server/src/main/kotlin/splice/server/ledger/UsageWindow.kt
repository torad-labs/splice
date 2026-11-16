// NEW: server mode (docs/specs/server-mode.md, F5). The stretch of time a usage question is asked over, as the CLI, the
// control plane and an admin's key ask it: `since=<duration>` (the last hour, `1h`; the last week, `7d`), or
// `from=<instant>` and `to=<instant>` (ISO-8601), either of which may be left out (from the first row, up to now).
// A request's row belongs to the window it arrived in, `from` included and `to` not.
package splice.server.ledger

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.topology.ServerDurations
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException

/** From [from], included, up to [to], not. */
internal data class UsageWindow(val from: Instant, val to: Instant) {
    /** As every answer names it: `{"from", "to"}`, ISO-8601 UTC. */
    public fun json(): JsonObject = buildJsonObject {
        put("from", from.toString())
        put("to", to.toString())
    }
}

/** A window as it was asked for, or why it cannot be read. */
internal sealed class WindowReading {
    data class Read(val window: UsageWindow) : WindowReading()

    data class Unread(val why: String) : WindowReading()
}

internal class UsageWindows(private val clock: Clock) {
    /** The window [query] names with its `since`, `from` and `to`. */
    public fun of(query: Map<String, String>): WindowReading {
        val since = query[SINCE]
        return when {
            since != null && (FROM in query || TO in query) -> WindowReading.Unread("name since, or from and to")
            since != null -> since(since)
            else -> between(query[FROM], query[TO])
        }
    }

    /** The last [text], a duration, up to now. */
    private fun since(text: String): WindowReading {
        val ms = ServerDurations.read(text) ?: return WindowReading.Unread(NOT_A_DURATION)
        val now = clock.instant()
        return WindowReading.Read(UsageWindow(now.minusMillis(ms), now))
    }

    /** From [from] to [to], from the first row and up to now where either is left out. */
    private fun between(from: String?, to: String?): WindowReading {
        val start = if (from == null) Instant.EPOCH else instant(from)
        val end = if (to == null) clock.instant() else instant(to)
        if (start == null || end == null) return WindowReading.Unread(NOT_AN_INSTANT)
        return WindowReading.Read(UsageWindow(start, end))
    }

    private fun instant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
}

// The query's names for a window.
private const val SINCE = "since"
private const val FROM = "from"
private const val TO = "to"

private const val NOT_A_DURATION = "since must be a duration such as 30m, 1h, 7d or 4w"
private const val NOT_AN_INSTANT = "from and to must each be an instant such as 2026-11-01T00:00:00Z"
