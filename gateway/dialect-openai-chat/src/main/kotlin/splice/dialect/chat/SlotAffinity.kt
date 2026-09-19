// NEW: V4-165 (2026-09-19) — a conversation keeps its llama-server slot.
//
// llama-server chooses a slot by prompt similarity measured against the NEW prompt, and it skips
// empty slots while doing so (prism fork server-context.cpp:1472-1517). Every Claude Code
// conversation opens with the same ~30K of system prompt and tools, so a new session's first
// request scores ~1.0 against an idle conversation's slot and takes it while other slots sit empty.
// The conversation it displaced then re-prefills from zero: the model's recurrent layers cannot be
// rewound, so a slot's state is all or nothing. `id_slot` is the server's own override, and this
// class decides it.
//
// THE KEY IS THE CONVERSATION, NOT THE SESSION. Claude Code's subagents and its title requests
// carry the main conversation's session id; pinned by session they would queue behind the main
// conversation on one slot and evict it each time. A conversation is its session plus its opening
// (system prompt and first message), which every later turn of it repeats verbatim and a subagent
// does not share. A compaction repeats it too, so it runs on the slot that already holds the text.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Maps conversations to slots: a conversation keeps the slot it had; a new one takes the
 *  least-recently-used slot with no turn in flight. With every slot busy, or the slot count not
 *  yet known, the request goes out unpinned — exactly what every request did before this class. */
public class SlotAffinity(private val slotCount: SlotCount) {

    /** The runtime's slot count, or null while it cannot be read (the server is down). */
    public fun interface SlotCount {
        public fun read(): Int?
    }

    private val lock = Any()
    private var slots: Slots? = null

    /** The conversation a request belongs to: its session and its OPENING — every message up to and
     *  including the first that is not the system prompt. Hashed, so the key holds no transcript. */
    public fun conversationOf(sessionId: String?, messages: JsonArray): String {
        val opening = messages.indexOfFirst { JsonScalars.str(it as? JsonObject, "role") != "system" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sessionId.orEmpty().toByteArray())
        messages.take(opening + 1).forEach { digest.update(it.toString().toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** A slot for [conversation], held until [SlotLease.end]; null = send this turn unpinned. */
    public fun lease(conversation: String): SlotLease? {
        // Read outside the lock: a runtime that is down must not stall every other turn's lease.
        val known = synchronized(lock) { slots } ?: slotCount.read()?.takeIf { it > 0 }?.let(::Slots)
        return synchronized(lock) {
            val table = slots ?: known?.also { slots = it } ?: return null
            val slot = table.pick(conversation) ?: return null
            table.start(slot, conversation)
            SlotLease(slot, this)
        }
    }

    /** The end of a [SlotLease] on [slot]: the slot is free for another conversation again. */
    internal fun finish(slot: Int) {
        synchronized(lock) { slots?.finish(slot) }
    }

    /** Per-slot bookkeeping, always touched under the owner's lock. */
    private class Slots(count: Int) {
        private val owner = arrayOfNulls<String>(count)
        private val inFlight = IntArray(count)
        private val lastUsed = LongArray(count)
        private var tick = 0L

        fun pick(conversation: String): Int? =
            owner.indexOf(conversation).takeIf { it >= 0 }
                ?: owner.indices.filter { inFlight[it] == 0 }.minByOrNull { lastUsed[it] }

        fun start(slot: Int, conversation: String) {
            owner[slot] = conversation
            inFlight[slot] += 1
            lastUsed[slot] = ++tick
        }

        fun finish(slot: Int) {
            inFlight[slot] = maxOf(0, inFlight[slot] - 1)
            lastUsed[slot] = ++tick
        }
    }
}

/** One turn's hold on [slot]; [end] releases it, and only the first call counts. */
public class SlotLease internal constructor(public val slot: Int, private val owner: SlotAffinity) {
    private val ended = AtomicBoolean(false)

    public fun end() {
        if (ended.compareAndSet(false, true)) owner.finish(slot)
    }
}
