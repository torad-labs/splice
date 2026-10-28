// NEW: failure-handling slice 0, the harness. The words a scripted reply is written in: the raw steps the provider
// takes (statuses, headers, bodies, events, keepalives, delays, holds, cuts and closes), and each API type's frames
// built from them, so a test scripts "an Anthropic error after some text" without spelling the JSON.
package splice.head.scripted

import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/** One step of a scripted reply, in the order the provider takes them. */
public sealed class ReplyStep {
    /** True for a step that sends part of a stream; on HTTP the first one sends the status and headers. */
    internal open val streams: Boolean get() = false

    /** Plays this step on [wire]; false ends the reply here. */
    internal abstract suspend fun playOn(wire: ReplyWire): Boolean

    /** The HTTP status; 200 when a reply names none. After the stream began, or on a websocket round, it changes
     *  nothing. */
    public data class Status(val code: Int) : ReplyStep() {
        override suspend fun playOn(wire: ReplyWire): Boolean = wire.status(code)
    }

    /** A response header. After the stream began, or on a websocket round, it changes nothing. */
    public data class Header(val name: String, val value: String) : ReplyStep() {
        override suspend fun playOn(wire: ReplyWire): Boolean = wire.header(name, value)
    }

    /** A whole JSON body, which ends the reply. On a websocket round it is one text frame. */
    public data class Body(val json: String) : ReplyStep() {
        override suspend fun playOn(wire: ReplyWire): Boolean = wire.body(json)
    }

    /** One server-sent event, or one text frame on a websocket round, where [name] is not sent. */
    public data class Event(val data: String, val name: String? = null) : ReplyStep() {
        override val streams: Boolean get() = true

        override suspend fun playOn(wire: ReplyWire): Boolean = wire.event(data, name)
    }

    /** A keepalive: an SSE comment, or a ping frame on a websocket round. */
    public data object Keepalive : ReplyStep() {
        override val streams: Boolean get() = true

        override suspend fun playOn(wire: ReplyWire): Boolean = wire.keepalive()
    }

    /** A pause on the virtual clock. Delays add up from the request's arrival, so a test that advances the clock
     *  as soon as the request is logged never races the provider into parking late. */
    public data class Delay(val by: Duration) : ReplyStep() {
        override suspend fun playOn(wire: ReplyWire): Boolean = wire.delay(by)
    }

    /** Waits until the test calls [ScriptedProvider.release], or until the head hangs up. */
    public data object Hold : ReplyStep() {
        override suspend fun playOn(wire: ReplyWire): Boolean = wire.hold()
    }

    /** A reply-local delivery proof, independent of every other held reply on this provider. */
    public data class AwaitSignal(val signal: Deferred<Unit>) : ReplyStep() {
        override suspend fun playOn(wire: ReplyWire): Boolean {
            signal.await()
            return true
        }
    }

    /**
     * Drops the connection where the reply stands: no end of stream, no close frame. Bytes written just before it
     * may still be in flight when the connection drops, so a test that needs them delivered holds first and
     * releases once the client has them.
     */
    public data object Cut : ReplyStep() {
        override suspend fun playOn(wire: ReplyWire): Boolean = wire.cut()
    }

    /** Closes a websocket round with [code] and [reason]. On HTTP the answer ends there, complete. */
    public data class Close(val code: Int, val reason: String = "") : ReplyStep() {
        override suspend fun playOn(wire: ReplyWire): Boolean = wire.close(code, reason)
    }
}

/** A reply, ready to script. Built with [reply]. */
public class ScriptedReply internal constructor(private val steps: List<ReplyStep>) {
    /** Every step, in order: a websocket round plays them all on one wire. */
    internal fun steps(): List<ReplyStep> = steps

    /** The steps before the first one that streams: on HTTP, everything that comes before the status goes out. */
    internal fun prelude(): List<ReplyStep> = steps.take(streamAt())

    /** The steps from the first one that streams on; none for a reply that never streams. */
    internal fun stream(): List<ReplyStep> = steps.drop(streamAt())

    private fun streamAt(): Int = steps.indexOfFirst { it.streams }.takeIf { it >= 0 } ?: steps.size
}

/** Writes a string as a JSON string literal, quotes and escapes included. */
private object JsonText {
    fun quoted(text: String): String = JsonPrimitive(text).toString()
}

/** The two raw steps every API type's frames are written in. */
public interface ReplyFrames {
    /** One server-sent event, or one websocket text frame. */
    public fun event(data: String, name: String? = null)

    /** A whole JSON body. */
    public fun body(json: String)
}

/** Anthropic Messages, as a provider streams it. */
public interface AnthropicFrames : ReplyFrames {
    /** message_start and an open text block. */
    public fun anthropicStart() {
        event(
            """{"type":"message_start","message":{"id":"msg_scripted","type":"message","role":"assistant",""" +
                """"model":"scripted","content":[],"stop_reason":null,"stop_sequence":null,""" +
                """"usage":{"input_tokens":12,"output_tokens":1}}}""",
            "message_start",
        )
        event(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_start",
        )
    }

    /** Text into the open block. */
    public fun anthropicText(text: String) {
        event(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":""" +
                "${JsonText.quoted(text)}}}",
            "content_block_delta",
        )
    }

    /** The block closed and the message ended with [stopReason]. */
    public fun anthropicEnd(stopReason: String = "end_turn") {
        event("""{"type":"content_block_stop","index":0}""", "content_block_stop")
        event(
            """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},""" +
                """"usage":{"output_tokens":5}}""",
            "message_delta",
        )
        event("""{"type":"message_stop"}""", "message_stop")
    }

    /** A whole answer of [text]. */
    public fun anthropicAnswer(text: String) {
        anthropicStart()
        anthropicText(text)
        anthropicEnd()
    }

    /** An error event inside the stream. */
    public fun anthropicError(type: String, message: String) {
        event(anthropicErrorJson(type, message), "error")
    }

    /** An error as a whole JSON body, for an HTTP status. */
    public fun anthropicErrorBody(type: String, message: String) {
        body(anthropicErrorJson(type, message))
    }

    private fun anthropicErrorJson(type: String, message: String): String =
        """{"type":"error","error":{"type":"$type","message":${JsonText.quoted(message)}}}"""
}

/** OpenAI Chat Completions, as a provider streams it, and the error body OpenAI's APIs share. */
public interface ChatFrames : ReplyFrames {
    /** One chunk of text. */
    public fun chatText(text: String) {
        event(
            """{"id":"chatcmpl-scripted","object":"chat.completion.chunk","model":"scripted","choices":""" +
                """[{"index":0,"delta":{"role":"assistant","content":${JsonText.quoted(text)}},""" +
                """"finish_reason":null}]}""",
        )
    }

    /** The finish, the usage and [DONE]. */
    public fun chatEnd(finishReason: String = "stop") {
        event(
            """{"id":"chatcmpl-scripted","object":"chat.completion.chunk","model":"scripted",""" +
                """"choices":[{"index":0,"delta":{},"finish_reason":"$finishReason"}]}""",
        )
        event(
            """{"id":"chatcmpl-scripted","object":"chat.completion.chunk","model":"scripted","choices":[],""" +
                """"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}""",
        )
        event("[DONE]")
    }

    /** A whole answer of [text]. */
    public fun chatAnswer(text: String) {
        chatText(text)
        chatEnd()
    }

    /** An error as a whole JSON body, as OpenAI's Chat Completions and Responses APIs both write it. */
    public fun openAiErrorBody(type: String, message: String, code: String) {
        body("""{"error":{"message":${JsonText.quoted(message)},"type":"$type","param":null,"code":"$code"}}""")
    }
}

/** OpenAI Responses, as a provider streams it over HTTP or sends it over its websocket. */
public interface ResponsesFrames : ReplyFrames {
    /** response.created and an open message item. */
    public fun responsesStart() {
        event("""{"type":"response.created","response":{"id":"resp_scripted","status":"in_progress","output":[]}}""")
        event("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")
    }

    /** Text into the open message item. */
    public fun responsesText(text: String) {
        event("""{"type":"response.output_text.delta","output_index":0,"delta":${JsonText.quoted(text)}}""")
    }

    /** The item done and the response completed. */
    public fun responsesEnd() {
        event("""{"type":"response.output_item.done","output_index":0}""")
        event(
            """{"type":"response.completed","response":{"id":"resp_scripted","status":"completed","output":[],""" +
                """"usage":{"input_tokens":12,"output_tokens":5}}}""",
        )
    }

    /** A whole answer of [text]. */
    public fun responsesAnswer(text: String) {
        responsesStart()
        responsesText(text)
        responsesEnd()
    }

    /** response.created, then reasoning item [id] at output 0 closed with [encrypted]: the backend's own encrypted
     *  reasoning, which a later request carries back and splice never reads. It streams no summary, so none of it
     *  reaches the client's screen. Each round's item has an id of its own, as the backend gives it. */
    public fun responsesReasoning(encrypted: String, id: String = "rs_scripted") {
        event("""{"type":"response.created","response":{"id":"resp_scripted","status":"in_progress","output":[]}}""")
        event("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"$id"}}""")
        event(
            """{"type":"response.output_item.done","output_index":0,""" +
                """"item":{"type":"reasoning","id":"$id","encrypted_content":${JsonText.quoted(encrypted)}}}""",
        )
    }

    /** [text] as a whole message at output 1, after a reasoning item. */
    public fun responsesMessageAfterReasoning(text: String) {
        event("""{"type":"response.output_item.added","output_index":1,"item":{"type":"message"}}""")
        event("""{"type":"response.output_text.delta","output_index":1,"delta":${JsonText.quoted(text)}}""")
        event("""{"type":"response.output_item.done","output_index":1}""")
    }

    /** The response completed, billing [reasoningTokens] of reasoning in its usage. */
    public fun responsesCompleted(reasoningTokens: Long) {
        event(
            """{"type":"response.completed","response":{"id":"resp_scripted","status":"completed","output":[],""" +
                """"usage":{"input_tokens":12,"output_tokens":${reasoningTokens + ANSWER_TOKENS},""" +
                """"output_tokens_details":{"reasoning_tokens":$reasoningTokens}}}}""",
        )
    }
}

/** The tokens a scripted answer bills beside its reasoning. */
private const val ANSWER_TOKENS = 5L

/** The vocabulary a reply is written in: the raw steps, and the frames of each API type's answer. */
public class ReplyScript : AnthropicFrames, ChatFrames, ResponsesFrames {
    private val steps = mutableListOf<ReplyStep>()

    internal fun build(): ScriptedReply = ScriptedReply(steps.toList())

    public fun status(code: Int) {
        steps += ReplyStep.Status(code)
    }

    public fun header(name: String, value: String) {
        steps += ReplyStep.Header(name, value)
    }

    override fun body(json: String) {
        steps += ReplyStep.Body(json)
    }

    override fun event(data: String, name: String?) {
        steps += ReplyStep.Event(data, name)
    }

    public fun keepalive() {
        steps += ReplyStep.Keepalive
    }

    public fun delay(by: Duration) {
        steps += ReplyStep.Delay(by)
    }

    public fun hold() {
        steps += ReplyStep.Hold
    }

    public fun awaitSignal(signal: Deferred<Unit>) {
        steps += ReplyStep.AwaitSignal(signal)
    }

    public fun cut() {
        steps += ReplyStep.Cut
    }

    public fun close(code: Int, reason: String = "") {
        steps += ReplyStep.Close(code, reason)
    }
}

/** A reply written in [ReplyScript]'s vocabulary. */
public fun reply(build: ReplyScript.() -> Unit): ScriptedReply = ReplyScript().apply(build).build()
