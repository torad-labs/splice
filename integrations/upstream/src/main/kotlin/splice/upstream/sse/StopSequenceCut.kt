// NEW (Nov 14, 2026): Anthropic's stop_sequences on a backend that has none of its own.
//
// A client that sends stop_sequences reads the answer up to the first one and an ending that names it. Claude Code's
// auto-mode review sends `["</block>"]` and reads the verdict before it. The OpenAI Responses API has no stop
// parameter, and splice's chat dialect never sent one, so the Responses dialect refused every such request and the
// chat dialect let the answer run on past the stop. This sink makes the cut on the way to the client instead: text
// stops at the first stop string, the string itself is not shown, and [matched] names it for the turn's ending
// (TurnOutcome.Success.stopSequence), where the gateway's terminal writes stop_reason `stop_sequence`.
//
// The upstream keeps generating until it ends on its own; only what reaches the client is cut. Text that could be the
// start of a stop string is held until the next delta settles it, so a stop split across two deltas is never half
// shown.
package splice.upstream.sse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex

/** The stop strings a request asks for, read from its raw Anthropic body. */
public class RequestedStops {
    /** The non-empty strings of `stop_sequences`, in order; empty when the request sends none or sends it in another
     *  shape. */
    public fun of(raw: JsonObject): List<String> =
        (raw[STOP_SEQUENCES] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            .filter(String::isNotEmpty)
}

/** [sink] with its text cut at the first of [stops]. Everything that is not text passes through untouched. */
public class StopSequenceCut(private val sink: WireSink, stops: List<String>) : WireSink by sink {
    private val stops = stops.filter(String::isNotEmpty)
    private val longest = this.stops.maxOfOrNull(String::length) ?: 0
    private val held = LinkedHashMap<WireBlockIndex, StringBuilder>()
    private val shown = StringBuilder()

    /** The stop string the text was cut at, or null while none has appeared. */
    public var matched: String? = null
        private set

    /** All the text the client was shown, across blocks, once a stop has cut it: the answer's text as the client
     *  holds it. */
    public val shownText: String get() = shown.toString()

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        if (matched != null || text.isEmpty()) return
        val pending = held.getOrPut(index) { StringBuilder() }.append(text)
        val hit = firstStop(pending)
        if (hit != null) {
            held.remove(index)
            show(index, pending.substring(0, hit.at))
            matched = hit.stop
            return
        }
        val settled = pending.length - heldTail(pending)
        if (settled > 0) {
            show(index, pending.substring(0, settled))
            pending.delete(0, settled)
        }
    }

    override suspend fun addTextBlock(text: String) {
        if (matched != null) return
        val hit = firstStop(text)
        val kept = hit?.let { text.substring(0, it.at) } ?: text
        shown.append(kept)
        sink.addTextBlock(kept)
        hit?.let { matched = it.stop }
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        release(index)
        sink.closeBlock(index)
    }

    override suspend fun closeAll() {
        release()
        sink.closeAll()
    }

    /** A round whose output is dropped takes its cut with it: the next round starts uncut. */
    override fun discard() {
        held.clear()
        shown.setLength(0)
        matched = null
        sink.discard()
    }

    /** Shows the text still held, in every open block: no stop followed it. Call it before a block closes through
     *  any sink but this one. */
    public suspend fun release() {
        held.keys.toList().forEach { release(it) }
    }

    private suspend fun release(index: WireBlockIndex) {
        val pending = held.remove(index) ?: return
        if (matched == null && pending.isNotEmpty()) show(index, pending.toString())
    }

    private suspend fun show(index: WireBlockIndex, text: String) {
        if (text.isEmpty()) return
        shown.append(text)
        sink.textDelta(index, text)
    }

    /** The stop that completes first in [text]: generation ends where a stop string is finished, so the earliest end
     *  wins, and between two ending together the longer, which began first. */
    private fun firstStop(text: CharSequence): Hit? = stops
        .mapNotNull { stop -> text.indexOf(stop).takeIf { it >= 0 }?.let { Hit(it, stop) } }
        .minWithOrNull(compareBy<Hit> { it.at + it.stop.length }.thenBy { it.at })

    /** How many characters at the end of [text] could begin a stop string: they wait for the next delta. */
    private fun heldTail(text: CharSequence): Int {
        for (size in minOf(text.length, longest - 1) downTo 1) {
            val tail = text.subSequence(text.length - size, text.length)
            if (stops.any { it.length > size && it.startsWith(tail) }) return size
        }
        return 0
    }

    private data class Hit(val at: Int, val stop: String)
}

private const val STOP_SEQUENCES = "stop_sequences"
