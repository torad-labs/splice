// NEW: the harness (docs/specs/harness.md, decisions 4 and 6). One call of one capability's worker for one turn,
// within its time limit, and what its end means for the turn: text to place, a turn let pass, a refusal, a failure.
package splice.harness

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.capability.Capability
import splice.core.capability.CapabilityField
import splice.core.capability.CapabilityWorker
import splice.head.port.HarnessRequest
import splice.head.port.HarnessTurn

/** What one capability's work came to for the turn. */
internal sealed class CallEnd {
    /** Text to place in the first user message. */
    data class Placed(val text: String) : CallEnd()

    /** The turn goes on and nothing is placed: a capability that places nothing let it pass. */
    data object Passed : CallEnd()

    /** The turn is refused with [reason], said as the worker said it. */
    data class Refused(val reason: String) : CallEnd()

    /** The work could not finish ([why] never holds a key); the declaration's `on_failure` decides the turn. */
    data class Failed(val why: String) : CallEnd()
}

/** The facts a call sends: the turn it is for and the request's own. */
internal data class CallFor(val head: String, val turn: HarnessTurn, val request: HarnessRequest)

internal class CapabilityCall(private val worker: HttpWorker) {
    suspend fun call(capability: Capability, on: CallFor): CallEnd {
        val http = capability.work.worker as? CapabilityWorker.Http
            ?: return CallEnd.Failed("its worker does not run in this version")
        val limitMs = capability.limits.timeoutS * MS_PER_S
        val end = withTimeoutOrNull(limitMs) { worker.call(http, body(capability, on), WorkerProgress { }) }
            ?: return CallEnd.Failed("it passed its time limit of ${capability.limits.timeoutS} s")
        return meaning(capability, end)
    }

    /** A result is placed where the capability places text, and lets the turn pass where it places none; a refusal
     *  refuses only from a capability that may refuse, and is a failure from one that may not. */
    private fun meaning(capability: Capability, end: WorkerEnd): CallEnd = when (end) {
        is WorkerEnd.Result -> if (capability.result.places && end.text.isNotEmpty()) {
            CallEnd.Placed(end.text)
        } else {
            CallEnd.Passed
        }
        is WorkerEnd.Refusal -> if (capability.result.mayRefuse) {
            CallEnd.Refused(end.reason)
        } else {
            CallEnd.Failed("it refused the turn, which it may not")
        }
        is WorkerEnd.Failed -> CallEnd.Failed(end.why)
    }

    /** The one object a worker is posted: the capability, the session and the turn, and the fields its declaration
     *  names, no others. */
    private fun body(capability: Capability, on: CallFor): JsonObject = buildJsonObject {
        put("capability", capability.name)
        put("session", on.request.sessionId)
        put("turn", on.turn.id)
        put(
            "fields",
            buildJsonObject {
                capability.work.send.forEach { field -> put(field.wire, value(field, on)) }
            },
        )
    }

    private fun value(field: CapabilityField, on: CallFor) = when (field) {
        CapabilityField.FIRST_USER_MESSAGE -> JsonPrimitive(on.request.firstUserText())
        CapabilityField.FOLDER -> on.turn.folder(on.request.sessionId)?.let { JsonPrimitive(it.toString()) } ?: JsonNull
        CapabilityField.MODEL -> JsonPrimitive(on.request.model)
        CapabilityField.HEAD -> JsonPrimitive(on.head)
    }
}

/** why: `timeout_s` is in seconds, the wait in milliseconds. */
private const val MS_PER_S = 1_000L
