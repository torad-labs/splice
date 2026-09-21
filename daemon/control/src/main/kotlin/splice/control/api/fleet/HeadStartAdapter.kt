// NEW: the seam between the control plane and the features/heads slice — adapts HeadResolver to
// StartHeadResolver so the feature can resolve and start a head without importing ManagedHead or any
// other control-plane record (the 2026-09-21 head-start extraction).
package splice.control.api.fleet

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import splice.control.api.HeadResolver
import splice.heads.start.StartHeadResolver
import splice.heads.start.StartHeadTarget

/** Adapts the shared lookup without exposing control-plane records to the feature. */
internal class HeadStartAdapter(
    private val resolver: HeadResolver,
) : StartHeadResolver {
    override suspend fun resolveOrRespond(call: ApplicationCall, name: String): StartHeadTarget? {
        val managed = resolver.resolveHeadOrRespond(call, name) ?: return null
        return object : StartHeadTarget {
            override suspend fun start(): Unit = managed.head.start()

            override fun status(): JsonObject = resolver.headStatus(managed)
        }
    }
}
