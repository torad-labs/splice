// The write side of one mock-upstream response: the SSE framing, the raw byte writes a scenario makes, and the
// event shapes the Responses stream is built from. Split out of MockChatGptUpstream so the scenarios read as
// event sequences and the mock keeps only the routing.
package splice.head

import com.sun.net.httpserver.HttpExchange

/** One response being written to the head. [pacer] is the mock's wall-clock seam (V4-111), reached only by [pause]. */
internal class MockSseWire(val exchange: HttpExchange, private val pacer: (Long) -> Unit) {
    val events = MockEvents()

    fun sse(json: String) {
        exchange.responseBody.write("data: $json\n\n".toByteArray())
        exchange.responseBody.flush()
    }

    fun write(text: String) = exchange.responseBody.write(text.toByteArray())

    fun writeBytes(bytes: ByteArray) = exchange.responseBody.write(bytes)

    fun flush() = exchange.responseBody.flush()

    fun pause(millis: Long) = pacer(millis)

    /** A complete non-stream answer: the status, the body's length, then the body. */
    fun respond(status: Int, body: String) {
        exchange.sendResponseHeaders(status, body.length.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }
}

/** The JSON of each Responses event a scenario sends. Indexes default to the first output item. */
internal class MockEvents {
    fun messageAdded(index: Int = 0) =
        """{"type":"response.output_item.added","output_index":$index,"item":{"type":"message"}}"""

    fun textDelta(text: String, index: Int = 0) =
        """{"type":"response.output_text.delta","output_index":$index,"delta":"$text"}"""

    fun itemDone(index: Int = 0) = """{"type":"response.output_item.done","output_index":$index}"""

    fun reasoningAdded(id: String) =
        """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"$id"}}"""

    fun summaryDelta(text: String) =
        """{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"$text"}"""

    fun summaryDone(itemId: String, summaryIndex: Int, text: String) =
        """{"type":"response.reasoning_summary_text.done","item_id":"$itemId","output_index":0,""" +
            """"summary_index":$summaryIndex,"text":"$text"}"""

    fun completed(id: String, input: Int, output: Int) =
        """{"type":"response.completed","response":{"id":"$id","status":"completed","output":[],""" +
            """"usage":{"input_tokens":$input,"output_tokens":$output}}}"""

    fun completedWithReasoning(id: String, input: Int, output: Int, reasoning: Int) =
        """{"type":"response.completed","response":{"id":"$id","status":"completed","output":[],""" +
            """"usage":{"input_tokens":$input,"output_tokens":$output,""" +
            """"output_tokens_details":{"reasoning_tokens":$reasoning}}}}"""

    fun failed(code: String, message: String) =
        """{"type":"response.failed","response":{"error":{"code":"$code","message":"$message"}}}"""
}
