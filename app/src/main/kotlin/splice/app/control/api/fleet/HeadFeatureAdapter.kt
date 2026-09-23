// NEW: the seam between the control plane and the features/heads slice — adapts the control-plane
// resolver to the feature-owned resolver so the feature owns lifecycle and log-retrieval sequences
// without importing ManagedHead or any other control-plane record.
package splice.app.control.api.fleet

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import splice.app.control.api.HeadResolver
import splice.heads.HeadStatus
import splice.heads.HeadTarget
import splice.heads.HeadResolver as FeatureHeadResolver

/** Adapts the shared lookup without exposing control-plane records to the feature. */
internal class HeadFeatureAdapter(
    private val resolver: HeadResolver,
) : FeatureHeadResolver {
    override suspend fun resolveOrRespond(call: ApplicationCall, name: String): HeadTarget? {
        val managed = resolver.resolveHeadOrRespond(call, name) ?: return null
        return object : HeadTarget {
            override suspend fun start(): Unit = managed.head.start()

            override suspend fun stop(): Unit = managed.head.stop()

            override suspend fun restart(): Unit = managed.head.restart()

            override fun status(): JsonObject = HeadStatus.json(managed.head, managed.authKind)

            override fun tailLogs(tail: Int): String = managed.logs.tail(tail)

            override fun logPath(): String = managed.logs.path()
        }
    }
}
