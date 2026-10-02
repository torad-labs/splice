// NEW: bounded protocol metrics observe host engines and executing cells without exposing identities.
package splice.codemode.host

import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import splice.codemode.CodeModeFields
import splice.codemode.CodeModeHeap
import splice.codemode.HostProtocol
import splice.codemode.SharedWorkerChannel
import java.io.IOException

internal class CodeModeHostMetrics {
    suspend fun total(current: List<Deferred<SharedWorkerChannel>>, key: String): Int = current.sumOf { boot ->
        val channel = boot.await()
        if (channel.isClosed) 0 else metric(channel.exchange(1, HostProtocol.command("engines")), key)
    }

    fun count(reply: JsonObject): Int = metric(reply, "count").also {
        if (it > CodeModeHeap.maxEnginesPerHost) throw IOException("Invalid engine count")
    }

    private fun metric(reply: JsonObject, key: String): Int {
        CodeModeFields.requireKeys(reply, setOf("type", "count", "busy"))
        check(CodeModeFields.requiredString(reply, "type") == "engines") { "Invalid engine reply" }
        return reply[key]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
            ?: throw IOException("Invalid engine metric")
    }
}
