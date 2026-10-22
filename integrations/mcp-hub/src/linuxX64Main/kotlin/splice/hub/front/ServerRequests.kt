package splice.hub.front

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A server request stays charged until its response flushes or the exact holder generation exits. */
class ServerRequests(
    changed: () -> Unit,
    checkpoint: () -> Boolean,
) {
    private val persistence = HolderPersistence(changed, checkpoint)
    private val ledger = ServerRequestLedger()
    private val delivery = ServerRequestDelivery(ledger, persistence)

    val size: Int get() = ledger.requests.size
    val busy: Set<String> get() = ledger.requests.values.map { it.holder.socket }.toSet()

    fun count(socket: String?): Int = ledger.requests.values.count { it.holder.socket == socket }

    fun awaitsExit(holder: Holder, generation: String): Boolean =
        ledger.requests.values.any { it.holder === holder && it.generation == generation }

    val retainedGenerations: List<Pair<Holder, String>>
        get() = ledger.requests.values.map { it.holder to it.generation }.distinct()

    /** Keeps the server request before it can reach a session. A failed checkpoint leaves it charged but unforwarded. */
    fun retain(holder: Holder, id: JsonElement): String? {
        val key = ledger.retain(holder, id) ?: return null
        delivery.remembers(holder)
        return key.takeIf(persistence::checkpoint)
    }

    fun bind(key: String, session: Session): Boolean = ledger.bind(key, session, persistence)

    fun restore(saved: JsonObject?, holder: (String) -> Holder, sessions: Map<String, Session>) {
        ledger.restore(saved, holder, sessions, delivery::remembers)
    }

    fun state(): JsonObject = ledger.state()

    fun answer(session: Session, msg: JsonObject, id: JsonElement) {
        delivery.answer(session, msg, id)
    }

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
