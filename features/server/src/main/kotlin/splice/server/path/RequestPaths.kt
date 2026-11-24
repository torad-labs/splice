// NEW: server mode (docs/specs/server-mode.md, F3), from the second review of its per-person limits (Nov 15, 2026).
// A request's path, read once, the way the head it may be relayed to reads one. A head's routing splits a path at `/`,
// drops the empty segments and percent-decodes each of the rest, so `/v1/%6dessages` is a turn to it. The listener
// judged the text as it was sent and relayed that text: a turn spelled that way was served and counted against
// neither of its person's caps, and so was `/v1/messages#x`, whose `#x` was dropped on the way to the head. Eight
// such turns of one person capped at one ran at once.
//
// So the listener decides everything on the segments as a head reads them, and a head is handed a path spelled back
// from those segments, never the client's own text. A head cannot read a path another way than the listener judged it.
package splice.server.path

import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.queryString

internal class RequestPaths {
    /** [call]'s path as its segments: split at `/`, the empty ones dropped, each percent-decoded once. Null when a `%`
     *  in it starts no escape, which spells no path. */
    fun read(call: ApplicationCall): List<String>? = try {
        call.request.path().split('/').filter(String::isNotEmpty).map { it.decodeURLPart() }
    } catch (_: URLDecodeException) {
        null
    }

    /** [segments] as a path that reads back to exactly them: what in a segment would split a path or end it (`/`, `?`,
     *  `#`, `%`) goes escaped. */
    fun spell(segments: List<String>): String = segments.joinToString("/", prefix = "/") { it.encodeURLPathPart() }

    /** [call]'s query as it follows a path, `?` included; empty when it has none. */
    fun query(call: ApplicationCall): String =
        call.request.queryString().takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
}
