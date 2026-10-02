// NEW: one request's admission transfer and heap ownership settle together before its drive starts.
package splice.head.admission

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.perf.TurnPerf
import splice.head.turn.Preparation
import splice.upstream.TurnEnd
import splice.upstream.retry.InflightGate
import java.util.concurrent.atomic.AtomicBoolean

/** Per-request ownership, distinct from the counted permit shared by a held source and its continuations. */
internal class AdmittedTurn(
    var slot: InflightGate.Slot,
    val t0: Long,
    val perf: TurnPerf,
) {
    private val handedOff = AtomicBoolean(false)
    var materializedEnd: TurnEnd? = null

    /** The header permits bounded parsing. Only provider proof can retain the borrowed source permit. */
    suspend fun settle(call: ApplicationCall, prepared: Preparation, admission: AdmissionGate): Boolean {
        if (!slot.resumedSource || prepared !is Preparation.Ready) return true
        val built = prepared.built
        if (built.roundInterceptor?.resumesSource() == true) return true
        return replace(call, prepared, admission)
    }

    private suspend fun replace(call: ApplicationCall, prepared: Preparation.Ready, admission: AdmissionGate): Boolean {
        slot.release()
        var replaced = false
        return try {
            val fresh = admission.acquireFreshSlotOrRespond(call) ?: return false
            slot = fresh
            replaced = true
            true
        } finally {
            if (!replaced) prepared.built.onEnd?.ended()
        }
    }

    /** Bind to the settled permit, never the candidate borrowed only to parse this body. */
    fun retainRequest() {
        materializedEnd?.let(slot::onRelease)
        materializedEnd = null
    }

    fun markHandedOff() {
        handedOff.set(true)
    }

    fun wasHandedOff(): Boolean = handedOff.get()

    suspend fun close() = withContext(NonCancellable) {
        materializedEnd?.ended()
        if (!handedOff.get()) slot.release()
    }
}
