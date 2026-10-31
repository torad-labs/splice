package splice.control.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A process's admission survives a caller timing out or disconnecting. */
internal class McpAdmission {
    private enum class Phase { SERVING, DRAINING, REPLACING, STOPPED }

    private val lock = Any()
    private var phase = Phase.SERVING
    private var active = 0
    private var reopened = CompletableDeferred<Unit>().apply { complete(Unit) }

    suspend fun enter(): Boolean {
        while (true) {
            val wait = synchronized(lock) {
                if (phase == Phase.STOPPED) return false
                if (phase == Phase.SERVING) {
                    active++
                    null
                } else {
                    reopened
                }
            } ?: return true
            wait.await()
        }
    }

    fun leave() {
        synchronized(lock) { active-- }
    }

    fun pause() {
        synchronized(lock) {
            if (phase == Phase.SERVING) {
                phase = Phase.DRAINING
                reopened = CompletableDeferred()
            }
        }
    }

    fun resume(): Boolean = synchronized(lock) {
        if (phase == Phase.REPLACING || phase == Phase.STOPPED) return@synchronized false
        phase = Phase.SERVING
        reopened.complete(Unit)
        true
    }

    fun stop() {
        synchronized(lock) {
            phase = Phase.STOPPED
            reopened.complete(Unit)
        }
    }

    fun beginReplacement(): Boolean = synchronized(lock) {
        if (phase != Phase.DRAINING || active != 0) return@synchronized false
        phase = Phase.REPLACING
        true
    }

    fun finishReplacement() {
        synchronized(lock) { if (phase == Phase.REPLACING) phase = Phase.DRAINING }
    }

    val paused: Boolean get() = synchronized(lock) { phase != Phase.SERVING }
    val activeRequests: Int get() = synchronized(lock) { active }

    fun snapshot(pid: Long?, unsettled: List<Long>): JsonObject = synchronized(lock) {
        buildJsonObject {
            put("phase", phase.name.lowercase())
            put("pid", pid)
            put("active_requests", active)
            put("unsettled_request_ids", buildJsonArray { unsettled.sorted().forEach { add(it) } })
            put("backend_state_transfer", false)
        }
    }
}
