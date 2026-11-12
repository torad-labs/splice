package splice.hub.front

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The source of a reverse-admission refusal, separated from a retained request whose checkpoint failed. */
internal sealed interface ServerRequestRetention {
    data class Retained(val key: String) : ServerRequestRetention

    data object CapacityRefused : ServerRequestRetention

    data object PersistenceFailed : ServerRequestRetention
}

/** A server request stays charged until holder proof confirms child delivery or the exact generation exits. */
internal class ServerRequests(
    changed: () -> Unit,
    checkpoint: () -> Boolean,
    keys: RelayWireKeys = RelayWireKeys(),
) {
    private val persistence = HolderPersistence(changed, checkpoint)
    private val ledger = ServerRequestLedger(keys)
    private val delivery = ServerRequestDelivery(ledger, persistence)
    internal val reconciliation = ServerRequestReconciliation(ledger, persistence, delivery)

    val size: Int get() = ledger.requests.size
    val busy: Set<String> get() = ledger.requests.values.map { it.holder.socket }.toSet()

    fun count(socket: String?): Int = ledger.requests.values.count { it.holder.socket == socket }

    fun awaitsExit(holder: Holder, generation: String): Boolean =
        ledger.requests.values.any { it.holder === holder && it.generation == generation }

    val retainedGenerations: List<Pair<Holder, String>>
        get() = ledger.requests.values.map { it.holder to it.generation }.distinct()

    /** Keeps the server request before it can reach a session. A failed checkpoint retains it and owns its recovery. */
    internal fun retain(holder: Holder, id: JsonElement): ServerRequestRetention {
        val key = ledger.retain(holder, id) ?: return ServerRequestRetention.CapacityRefused
        if (persistence.checkpoint()) return ServerRequestRetention.Retained(key)
        delivery.interrupted(key)
        return ServerRequestRetention.PersistenceFailed
    }

    fun bind(key: String, session: Session): Boolean {
        val bound = ledger.bind(key, session, persistence)
        if (!bound) delivery.interrupted(key)
        return bound
    }

    fun restore(saved: JsonObject?, holder: (String) -> Holder, sessions: Map<String, Session>) {
        ledger.restore(saved, holder, sessions)
        delivery.recover()
    }

    fun state(): JsonObject = ledger.state()

    fun answer(session: Session, msg: JsonObject, id: JsonElement): Boolean? =
        delivery.answer(session, msg, id)

    fun forwarded(holder: Holder, id: JsonElement): Pair<String, Session>? = ledger.forwarded(holder, id)

    fun retire(session: Session, reason: String) {
        delivery.retire(session, reason)
    }

    fun reply(key: String, text: String) {
        delivery.reply(key, text)
    }

    fun exited(holder: Holder, generation: String) {
        ledger.removeGeneration(holder, generation)
    }
}
