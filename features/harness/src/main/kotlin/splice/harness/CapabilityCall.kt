// NEW: the harness (docs/specs/harness.md, decisions 4 and 6). One call of one capability's worker for one turn,
// within its time limit, and what its end means for the turn: text to place, a turn let pass, a refusal, a failure.
package splice.harness

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.capability.Capability
import splice.core.capability.CapabilityBuiltins
import splice.core.capability.CapabilityField
import splice.core.capability.CapabilityWorker
import splice.head.port.HarnessRequest
import splice.head.port.HarnessTurn
import java.util.concurrent.TimeUnit

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

/** The facts a call sends: the turn it is for and the request's own, and the input of the model's call of its tool
 *  (decision 12), which a call by rule has none of. */
internal data class CallFor(
    val head: String,
    val turn: HarnessTurn,
    val request: HarnessRequest,
    val input: JsonElement? = null,
)

internal class CapabilityCall(
    private val http: HttpWorker,
    private val mcp: McpWorker,
    private val getter: ContextGetter,
) {
    /** Why [capability] is not called for [on] though its rule names it, or null when it is: only the Context Getter
     *  has reasons of its own (decision 9). */
    suspend fun notCalled(capability: Capability, on: CallFor): String? =
        if (gathers(capability.work.worker)) getter.notCalled(on) else null

    suspend fun call(capability: Capability, on: CallFor): CallEnd = meaning(capability, work(capability, on))

    /** How [capability]'s worker ended for [on], within its time limit; [posted] hears when it has been asked. */
    suspend fun work(capability: Capability, on: CallFor, posted: WorkerPosted = WorkerPosted { }): WorkerEnd {
        // `timeout_s` is in seconds, the wait in milliseconds
        val limitMs = TimeUnit.SECONDS.toMillis(capability.limits.timeoutS.toLong())
        return withTimeoutOrNull(limitMs) { worked(capability, on, WorkerProgress { }, posted) }
            ?: WorkerEnd.Failed("it passed its time limit of ${capability.limits.timeoutS} s")
    }

    /** The capability's worker, run for the turn [on]. An HTTP worker tells [posted] once its body is sent; any other
     *  is asked as soon as its work starts. */
    private suspend fun worked(
        capability: Capability,
        on: CallFor,
        progress: WorkerProgress,
        posted: WorkerPosted,
    ): WorkerEnd = when (val worker = capability.work.worker) {
        is CapabilityWorker.Http -> http.call(worker, body(capability, on), progress, posted)
        is CapabilityWorker.Mcp -> {
            posted.posted()
            mcp.call(
                worker,
                on.head,
                on.turn.folder(on.request.sessionId),
                McpToolCall(fields(capability, on), identity(capability, on)),
                progress,
            )
        }
        is CapabilityWorker.Builtin -> {
            posted.posted()
            if (gathers(worker)) {
                getter.call(worker, on, capability.limits.timeoutS, progress)
            } else {
                WorkerEnd.Failed("its worker does not run in this version")
            }
        }
    }

    private fun gathers(worker: CapabilityWorker): Boolean =
        (worker as? CapabilityWorker.Builtin)?.name == CapabilityBuiltins.CONTEXT_GETTER

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
    private fun body(capability: Capability, on: CallFor): JsonObject =
        JsonObject(identity(capability, on) + ("fields" to fields(capability, on)))

    /** The fields the declaration names, each as the request has it, after the input of the model's call when there
     *  is one (decisions 12 and 13). */
    private fun fields(capability: Capability, on: CallFor): JsonObject = buildJsonObject {
        on.input?.let { put(INPUT, it) }
        capability.work.send.forEach { field -> put(field.wire, value(field, on)) }
    }

    /** Which capability and which turn the call is for: an HTTP worker's body carries it, and an MCP call's `_meta`. */
    private fun identity(capability: Capability, on: CallFor): JsonObject = buildJsonObject {
        put("capability", capability.name)
        put("session", on.request.sessionId)
        put("turn", on.turn.id)
    }

    private fun value(field: CapabilityField, on: CallFor) = when (field) {
        CapabilityField.FIRST_USER_MESSAGE -> JsonPrimitive(on.request.firstUserText())
        CapabilityField.FOLDER -> on.turn.folder(on.request.sessionId)?.let { JsonPrimitive(it.toString()) } ?: JsonNull
        CapabilityField.MODEL -> JsonPrimitive(on.request.model)
        CapabilityField.HEAD -> JsonPrimitive(on.head)
    }
}

/** The field the model's call sends its tool's input in, beside the fields `send` names. */
private const val INPUT = "input"
