// NEW: failure-handling slice 2 (2026-10-26), moved out of HeapIngress.kt. The port through which the listener renders
// a refusal the ingress makes before any turn exists.
package splice.http.ingress

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import splice.core.failure.Decline

/** The listener supplies its canonical pre-turn HTTP error renderer; transport never builds an SSE error. The words
 *  are the listener's to say: transport names only what it declined ([why]) and the error [type]. */
public fun interface IngressErrorBody {
    public fun render(type: String, why: Decline): String

    /** The listener may observe delivery while retaining the canonical rendered body and HTTP status. */
    public fun content(call: ApplicationCall, type: String, why: Decline, status: HttpStatusCode): OutgoingContent =
        TextContent(render(type, why), ContentType.Application.Json, status)
}
