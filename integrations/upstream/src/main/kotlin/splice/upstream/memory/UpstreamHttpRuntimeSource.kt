package splice.upstream.memory

import io.ktor.client.HttpClient
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import splice.core.perf.runtime.PerfRuntimeSource

/** These are only this client's HTTP pool and call dispatcher; websocket pools remain separate and unknown. */
public class UpstreamHttpRuntimeSource(
    private val pool: ConnectionPool,
    private val dispatcher: Dispatcher,
) : PerfRuntimeSource {
    override fun runtime(): JsonObject = buildJsonObject {
        put(
            "measurements",
            buildJsonObject {
                put("runtime_http_connections", pool.connectionCount())
                put("runtime_http_connections_idle", pool.idleConnectionCount())
                put("runtime_http_calls_queued", dispatcher.queuedCallsCount())
                put("runtime_http_calls_running", dispatcher.runningCallsCount())
            },
        )
    }
}

public object UpstreamHttpRuntime {
    public val key: AttributeKey<PerfRuntimeSource> = AttributeKey("splice.http.runtime")
    public fun source(client: HttpClient): PerfRuntimeSource? = client.attributes.getOrNull(key)
}
