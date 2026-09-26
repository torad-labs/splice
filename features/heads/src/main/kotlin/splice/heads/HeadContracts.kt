// NEW: feature-owned ports for head lifecycle, status, and log operations.
package splice.heads

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject

/** Resolves a route name to the one runtime head that must perform the request. */
public fun interface HeadResolver {
    /** Returns the resolved target or writes the lookup refusal to [call]. */
    public suspend fun resolveOrRespond(call: ApplicationCall, name: String): HeadTarget?
}

/** The runtime head used for lifecycle effects, live status, and log retrieval. */
public interface HeadTarget {
    public suspend fun start(): Unit

    public suspend fun stop(): Unit

    public suspend fun restart(): Unit

    /** Projects the status of this same resolved runtime instance. */
    public fun status(): JsonObject

    /** Reads the requested tail from this same resolved runtime instance. */
    public fun tailLogs(tail: Int): String

    /** Returns the log path for this same resolved runtime instance. */
    public fun logPath(): String
}

/** Records a successful lifecycle request under the name supplied by the caller. */
public fun interface HeadAudit {
    public fun record(name: String, action: String): Unit
}

/** Supplies head-status snapshots in registry order for the aggregate listing. */
public fun interface HeadStatusListing {
    public fun snapshots(): List<JsonObject>
}
