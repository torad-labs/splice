// NEW: server mode (docs/specs/server-mode.md, F5). The ledger's reading of one chat answer the server listener relays
// for an OpenAI-style client: the usage the server billed (read the way a head reads it, ChatUsage), llama-server's
// `timings`, the model the server named, whether the answer reached its end and the stream ended with `[DONE]`, when
// the model first said anything, and the longest it then fell silent (F6). It reads each frame as the relay passes it
// on, and keeps no word of the answer.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.server.AnswerReading
import splice.core.server.ServedTokens
import splice.core.server.TurnSpeed
import splice.core.turn.UsageField
import splice.core.util.JsonScalars
import java.time.Clock

public class ChatAnswerMeter internal constructor(private val clock: Clock = Clock.systemUTC()) : AnswerReading {
    private val usage = ChatUsage()
    private val json = ChatPassthrough()

    @Volatile
    private var reported = false

    @Volatile
    override var model: String? = null
        private set

    @Volatile
    override var finished: Boolean = false
        private set

    @Volatile
    override var ended: Boolean = false
        private set

    @Volatile
    override var spokeAtMs: Long? = null
        private set

    @Volatile
    override var stallMs: Long? = null
        private set

    // When the answer last carried anything of the model's, in epoch milliseconds.
    @Volatile
    private var lastSpokeMs: Long? = null

    override val tokens: ServedTokens?
        get() {
            val read = usage.toUsage().takeIf { reported } ?: return null
            return ServedTokens(read.inputTokens, read.cachedTokens, read.outputTokens)
        }

    override val speed: TurnSpeed? get() = usage.speed

    /** A whole answer, read once it has come. */
    public fun whole(body: ByteArray) {
        json.objectOf(String(body))?.let(::frame)
        finished = true
    }

    /** One frame's data, as the stream carried it. */
    internal fun data(data: String) {
        if (data == CHAT_DONE) ended = true else json.objectOf(data)?.let(::frame)
    }

    /** The server closed its stream cleanly, with or without `[DONE]`. */
    internal fun closed() {
        finished = true
    }

    private fun frame(frame: JsonObject) {
        JsonScalars.str(frame, FRAME_MODEL)?.let { model = it }
        usage.usage(frame)
        reported = reported || usage.toUsage().reported.any { it == UsageField.INPUT || it == UsageField.OUTPUT }
        if (speaks(frame)) spoke(clock.millis())
    }

    /** The model said something at [nowMs]: the first time is when it spoke, and each later one ends a silence. */
    private fun spoke(nowMs: Long) {
        val last = lastSpokeMs
        lastSpokeMs = nowMs
        if (last == null) {
            spokeAtMs = nowMs
            stallMs = 0L
        } else {
            stallMs = maxOf(stallMs ?: 0L, nowMs - last)
        }
    }

    /** Whether [frame]'s first choice carries any of the model's own output. */
    private fun speaks(frame: JsonObject): Boolean {
        val choice = (frame[FRAME_CHOICES] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
        val said = (choice[CHOICE_DELTA] ?: choice[CHOICE_MESSAGE]) as? JsonObject ?: return false
        return said.any { (field, value) -> field in MODEL_FIELDS && carries(value) }
    }

    /** Whether [value] holds anything: a string with text, or a list with an entry. */
    private fun carries(value: JsonElement): Boolean = when (value) {
        is JsonPrimitive -> value.isString && value.content.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> false
    }
}

// The fields of a frame the meter reads, and those of a choice's delta or message that carry the model's own output.
private const val FRAME_MODEL = "model"
private const val CHOICE_DELTA = "delta"
private const val CHOICE_MESSAGE = "message"
private val MODEL_FIELDS = setOf("content", "reasoning_content", "reasoning", "tool_calls", "refusal")
