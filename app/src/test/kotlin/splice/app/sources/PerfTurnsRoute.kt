// Drives GET /api/perf/turns the way the console does, so tests read the real payload without reaching into PerfRoutes.
package splice.app.sources

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.perf.PerfRoutes

internal class PerfTurnsRoute(private val head: UsageHead, clock: WallClock = WallClock { 200_000 }) {
    private val routes = PerfRoutes(UsageHeadLookup { listOf(head) }, clock)

    /** The response body for [since] and [n], narrowed by [filters] (the route's own query parameters). */
    fun response(
        since: Long,
        n: Int = 1,
        filters: Map<String, String> = emptyMap(),
        zone: String = "America/Chicago",
    ): JsonObject {
        val query = (mapOf("head" to head.key, "since" to "$since", "n" to "$n", "time_zone" to zone) + filters)
            .entries.joinToString("&") { (key, value) -> "$key=$value" }
        return runBlocking {
            var body = ""
            testApplication {
                application { routing { get("/api/perf/turns") { routes.turns(call) } } }
                body = client.get("/api/perf/turns?$query").bodyAsText()
            }
            Json.parseToJsonElement(body).jsonObject
        }
    }

    /** The one head's entry of [response]. */
    fun turns(
        since: Long,
        n: Int = 1,
        filters: Map<String, String> = emptyMap(),
        zone: String = "America/Chicago",
    ): JsonObject = response(since, n, filters, zone).getValue("heads").jsonArray.single().jsonObject
}
