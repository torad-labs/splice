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
//
// V4-166 (review, 2026-09-19): the slot count is read on EVERY lease, no longer once. A server
// restarted with another -np wraps an id past its count (id_slot % n, server-context.cpp:1433) onto a
// slot another conversation holds; a changed count now rebuilds the table, and a lease taken before
// the change ends on the table it came from, never on the new one.
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

    /** The runtime's slot count, or null while it cannot be read (the server is down). Read on every
     *  lease, on the request path, so it must answer from what it already knows and never wait. */
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
        // Read outside the lock: a runtime that is down must not stall every other turn's lease. An
        // unreadable count keeps the table it had; the runtime is down and this turn fails anyway.
        val count = slotCount.read()?.takeIf { it > 0 }
        return synchronized(lock) {
            if (count != null && count != slots?.size) slots = Slots(count)
            val table = slots ?: return null
            val slot = table.pick(conversation) ?: return null
            table.start(slot, conversation)
            SlotLease(slot, table, this)
        }
    }

    /** The end of a [SlotLease] on [slot] of [table]: the slot is free for another conversation again. */
    internal fun finish(table: Slots, slot: Int) {
        synchronized(lock) { table.finish(slot) }
    }

    /** Per-slot bookkeeping, always touched under the owner's lock. */
    internal class Slots(val size: Int) {
        private val owner = arrayOfNulls<String>(size)
        private val inFlight = IntArray(size)
        private val lastUsed = LongArray(size)
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

/** One turn's hold on [slot] of the table it was taken from; [end] releases it, and only the first
 *  call counts. */
public class SlotLease internal constructor(
    public val slot: Int,
    private val table: SlotAffinity.Slots,
    private val owner: SlotAffinity,
) {
    private val ended = AtomicBoolean(false)

    public fun end() {
        if (ended.compareAndSet(false, true)) owner.finish(table, slot)
    }
}
