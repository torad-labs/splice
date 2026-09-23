// PORT-OF: control/api/HeadResolver.kt — the heads capability owns its byte-stable status projection.
package splice.heads

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.GATEWAY_VERSION
import splice.core.head.Head

/** Projects the current state of one runtime head using the established control API shape. */
public object HeadStatus {
    public fun json(head: Head, authKind: String): JsonObject = buildJsonObject {
        val h = head.healthSnapshot()
        put("key", head.key)
        put("label", head.label)
        put("name", head.key)
        put("port", head.port)
        put("authKind", authKind)
        put("wantVersion", GATEWAY_VERSION)
        put("running", h.running)
        put("healthy", h.ok)
        put("version", if (h.running) GATEWAY_VERSION else null as String?)
        put("versionMatch", if (h.running) true else null as Boolean?)
        put("mode", null as String?)
        putJsonObject("gate") {
            put("inflight", h.gateInflight)
            put("queued", h.gateQueued)
            if (h.gateLimit <= 0) put("max", "unlimited") else put("max", h.gateLimit)
            // Preserve the legacy counter fields, which the in-process gate does not accumulate.
            put("acquired", 0)
            put("released", 0)
            put("waited", 0)
            put("avg_wait_ms", 0)
            putJsonArray("live") {}
            put("stream_idle_ms", 0)
        }
        put("maxInflight", if (h.gateLimit <= 0) null else h.gateLimit)
        putJsonObject("health") {
            put("localOriginErrors", h.localOriginErrors)
            put("providerErrors", h.providerErrors)
        }
        putJsonArray("pids") {}
    }
}
