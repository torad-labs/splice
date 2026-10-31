// NEW: moved out of HeapIngress.kt (failure-handling slice 2, 2026-10-26). How a routed call reaches the heap charge
// the ingress bound to it before the request was wrapped.
package splice.http.ingress

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import splice.core.memory.HeapLease

/** The bridge from a routed call to the ingress charge bound before its request was wrapped. */
public object IngressLeases {
    /** Where [HeapIngress] binds a request's charge on its call. */
    internal val key: AttributeKey<HeapLease> = AttributeKey("splice.heap.request")

    public fun borrow(call: ApplicationCall): HeapLease? = call.attributes.getOrNull(key)?.share()
}
