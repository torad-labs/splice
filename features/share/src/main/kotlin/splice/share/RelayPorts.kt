// NEW: server mode (docs/specs/server-mode.md, F4). The two questions ShareRelay asks of whoever hands it a request,
// one per route: which of the other machine's headers ride on with it, and what sends it and says how that ended.
package splice.share

/** Whether a header the other machine sent rides on with the request the relay passes on, asked by lowercase name. */
internal fun interface RidingHeader {
    operator fun invoke(name: String): Boolean
}

/** What asks for one request's answer and says how that ended: a head's one send, or a way through a provider's
 *  servers. An answer is kept in what it is handed from the moment there is one. */
internal fun interface Asking {
    suspend fun sent(held: HeldAnswer): Sent
}
