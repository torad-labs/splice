// NEW: failure-handling slice 2 (2026-10-26), moved out of HeapIngress.kt. The port through which the listener renders
// a refusal the ingress makes before any turn exists.
package splice.http.ingress

import splice.core.failure.Decline

/** The listener supplies its canonical pre-turn HTTP error renderer; transport never builds an SSE error. The words
 *  are the listener's to say: transport names only what it declined ([why]) and the error [type]. */
public fun interface IngressErrorBody {
    public fun render(type: String, why: Decline): String
}
