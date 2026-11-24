// NEW: server mode (#791, `[server] stream_idle_seconds`). A streamed answer whose upstream falls silent partway
// through, and stays silent, is ended with an error event its client retries, instead of hanging until a proxy or the
// person gives up. It is the operator's deadline, opt-in and off by default, because a model may think in silence and
// only the operator knows how long theirs does (CLAUDE.md: idle is a probe, never an error).
//
// The silence is timed from the answer's last word, and only once its first word has come: the wait for the first is
// a prefill, which `keepalive_seconds` keeps alive and nothing here ends. What carries no answer is no word: an SSE
// comment (a keepalive), a `ping` event, which a head writes on its own to learn whether its client is still there,
// and the head's own wait notice ("[splice] … has paused mid-answer"), which it writes while the model is silent.
// The time goes by the relay's clock (RelayClock), so a test steps it.
package splice.share

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.failure.SPLICE_PREFIX
import splice.core.turn.SpliceNotice
import splice.core.util.JsonScalars

internal class StreamIdle(private val ms: Long, private val clock: RelayClock, private val scope: CoroutineScope) {
    private val words = AnswerWords()

    // Ends once the answer has said nothing for [ms] since its last word; null before its first.
    private var timer: Job? = null

    /** What [wait] answers, or null once the answer has been silent too long, which cancels [wait]. Before the
     *  answer's first word nothing is timed, and with [ms] 0 nothing ever is. */
    suspend fun <T : Any> within(wait: HeadWait<T>): T? {
        val running = timer ?: return wait()
        return clock.before(running, wait)
    }

    /** [piece] of the answer came: when it holds a word, the silence is timed again from now. */
    fun heard(piece: ByteArray) {
        if (ms <= 0 || !words.said(piece)) return
        timer?.cancel()
        timer = clock.timer(scope, ms)
    }

    /** The answer ended, however it did: nothing is timed any more. */
    fun stop() {
        timer?.cancel()
    }
}

/** Reads whether the pieces of an event stream say anything of the answer, line by line, a line split across two
 *  pieces once its end has come. A word is any data line but the ones that carry none: a `ping`, the frames that open
 *  and close a message or a block, and a head's own wait notice (a thinking delta in splice's voice, and the signature
 *  that marks its block, SpliceNotice). A data line that is no JSON is a word: something came. */
internal class AnswerWords {
    private val line = StringBuilder()

    fun said(piece: ByteArray): Boolean {
        var word = false
        for (char in String(piece, Charsets.UTF_8)) {
            if (char == '\n') {
                word = isWord(line.toString().trimEnd('\r')) || word
                line.setLength(0)
            } else {
                line.append(char)
            }
        }
        return word
    }

    private fun isWord(text: String): Boolean {
        if (!text.startsWith(IDLE_DATA_LINE)) return false
        val data = text.removePrefix(IDLE_DATA_LINE).trim()
        val event = try {
            Json.parseToJsonElement(data) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return data.isNotEmpty()
        return when (JsonScalars.strOrEmpty(event[IDLE_TYPE_KEY])) {
            in structural -> false
            IDLE_DELTA_TYPE -> !notice(event[IDLE_DELTA_KEY] as? JsonObject)
            else -> true
        }
    }

    /** Whether [delta] is a head's wait notice rather than the model's: its line, or its block's signature. */
    private fun notice(delta: JsonObject?): Boolean {
        val thinking = JsonScalars.strOrEmpty(delta?.get(IDLE_THINKING_KEY)).trimStart('\n')
        val signature = JsonScalars.strOrEmpty(delta?.get(IDLE_SIGNATURE_KEY))
        return thinking.startsWith(SPLICE_PREFIX) || signature == SpliceNotice.SIGNATURE
    }

    // The frames that carry no word: a probe, and the frames that open or close a message or a block.
    private val structural = setOf("ping", "message_start", "content_block_start", "content_block_stop")
}

private const val IDLE_DATA_LINE = "data:"
private const val IDLE_TYPE_KEY = "type"
private const val IDLE_DELTA_TYPE = "content_block_delta"
private const val IDLE_DELTA_KEY = "delta"
private const val IDLE_THINKING_KEY = "thinking"
private const val IDLE_SIGNATURE_KEY = "signature"
