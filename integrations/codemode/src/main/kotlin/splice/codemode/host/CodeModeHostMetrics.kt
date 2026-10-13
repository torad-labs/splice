// NEW: a host's session-open reply, read as its engine count without exposing identities.
package splice.codemode.host

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import splice.codemode.CODE_MODE_FATAL_FRAME_TYPE
import splice.codemode.CodeModeFatalFrame
import splice.codemode.CodeModeFields
import splice.codemode.CodeModeHeap
import splice.codemode.DEFAULT_WORKER_START_TIMEOUT_MS
import splice.codemode.HostProtocol
import splice.codemode.SharedWorkerChannel
import splice.upstream.failure.CodeModeCapacityException
import java.io.IOException

internal class CodeModeHostMetrics(private val timeoutMs: Long = DEFAULT_WORKER_START_TIMEOUT_MS) {
    suspend fun admit(session: CodeModePoolSession, host: SharedWorkerChannel, admission: CodeModePoolAdmission) {
        val reply = host.control(session.id, HostProtocol.command("session-open"), session.id, timeoutMs)
        if (CodeModeFields.requiredString(reply, "type") == "capacity") {
            throw CodeModeCapacityException(
                "${admission.capacity().message}; ${CodeModeFields.requiredString(reply, "detail")}",
            )
        }
        count(reply)
    }

    fun count(reply: JsonObject): Int = engines(reply).also {
        if (it > CodeModeHeap.maxEnginesPerHost) throw IOException("Invalid engine count")
    }

    private fun engines(reply: JsonObject): Int {
        if (CodeModeFields.requiredString(reply, "type") == CODE_MODE_FATAL_FRAME_TYPE) CodeModeFatalFrame.parse(reply)
        CodeModeFields.requireKeys(reply, setOf("type", "count"))
        if (CodeModeFields.requiredString(reply, "type") != "engines") throw IOException("Invalid engine reply")
        return reply["count"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
            ?: throw IOException("Invalid engine metric")
    }
}
