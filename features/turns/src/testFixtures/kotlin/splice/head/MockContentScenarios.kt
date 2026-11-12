// The scenarios that stream an answer to its end: reasoning, text, a tool call, the odd shapes, and the default.
package splice.head

/** The 3-byte check mark the nonstream_tool body is split inside. */
private const val SPLIT_CODEPOINT = "✓"
private const val SPLIT_GAP_MS = 20L
private const val OUTPUT_ITEM = 1

internal class MockContentScenarios(private val wire: MockSseWire) {
    private val e = wire.events

    /** Plays [scenario]; anything unknown is the basic answer, which is what a request with no marker gets. */
    fun play(scenario: String) {
        when (scenario) {
            "multipart" -> multipart()
            "toolcall" -> toolCall()
            "bigout" -> bigOutput()
            "nonstream_tool" -> splitInsideCodepoint()
            "compactish" -> compactish()
            "replaystream" -> replayStream()
            "malformed_sse" -> malformed()
            else -> answer("r4") // basic / refresh-after-refresh
        }
    }

    private fun answer(id: String) {
        wire.sse(e.messageAdded())
        wire.sse(e.textDelta("ok after auth"))
        wire.sse(e.itemDone())
        wire.sse(e.completed(id, input = 1, output = 1))
    }

    private fun multipart() {
        wire.sse(e.reasoningAdded("rs_mp"))
        summaryPart("Part one.", summaryIndex = 0)
        summaryPart("Part two.", summaryIndex = 1)
        wire.sse(e.itemDone())
        wire.sse(e.messageAdded(OUTPUT_ITEM))
        wire.sse(e.textDelta("Answer text.", OUTPUT_ITEM))
        wire.sse(e.itemDone(OUTPUT_ITEM))
        wire.sse(e.completed("r1", input = 10, output = 5))
        wire.write("data: [DONE]\n\n")
    }

    private fun summaryPart(text: String, summaryIndex: Int) {
        wire.sse("""{"type":"response.reasoning_summary_part.added","output_index":0}""")
        wire.sse(e.summaryDelta(text))
        wire.sse(e.summaryDone("rs_mp", summaryIndex, text))
        wire.sse("""{"type":"response.reasoning_summary_part.done","output_index":0}""")
    }

    private fun toolCall() {
        wire.sse(
            """{"type":"response.output_item.added","output_index":0,""" +
                """"item":{"type":"function_call","call_id":"call_abc","name":"get_thing"}}""",
        )
        wire.sse("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"a\":"}""")
        wire.sse("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"1}"}""")
        wire.sse("""{"type":"response.function_call_arguments.done","output_index":0}""")
        wire.sse(e.itemDone())
        wire.sse(e.completed("r2", input = 4, output = 2))
    }

    private fun bigOutput() {
        wire.sse(e.messageAdded())
        wire.sse(e.textDelta("short summary"))
        wire.sse(e.itemDone())
        wire.sse(e.completed("rbig", input = 500, output = 200000))
    }

    private fun splitInsideCodepoint() {
        val evt = """{"type":"response.completed","response":{"id":"r3","status":"completed","output":[""" +
            """{"type":"reasoning","summary":[{"type":"summary_text",""" +
            """"text":"Because reasons that are long enough to mirror."}]},""" +
            """{"type":"message","content":[{"type":"output_text","text":"héllo — ✓ done"}]},""" +
            """{"type":"function_call","call_id":"call_xyz","name":"fn_x","arguments":"{\"q\":\"z\"}"}""" +
            """],"usage":{"input_tokens":3,"output_tokens":2}}}"""
        val buf = "data: $evt\n\n".toByteArray()
        val mark = SPLIT_CODEPOINT.toByteArray()
        val at = buf.toList()
            .windowed(mark.size)
            .indexOfFirst { it == mark.toList() } + 1
        wire.writeBytes(buf.copyOfRange(0, at)) // split INSIDE the 3-byte ✓
        wire.flush()
        wire.pause(SPLIT_GAP_MS)
        wire.writeBytes(buf.copyOfRange(at, buf.size))
    }

    private fun compactish() {
        val summary = "Goal: port the proxy. Decisions: split modules. Next: tests."
        wire.sse(e.reasoningAdded("rs_cp"))
        wire.sse(e.summaryDelta(summary))
        wire.sse(e.summaryDone("rs_cp", summaryIndex = 0, text = summary))
        wire.sse(e.itemDone())
        wire.sse(e.completed("rc", input = 9, output = 3))
    }

    private fun replayStream() {
        wire.sse("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}""")
        wire.sse(e.summaryDelta("Long enough reasoning summary to mirror into text."))
        wire.sse(
            """{"type":"response.output_item.done","output_index":0,""" +
                """"item":{"type":"reasoning","id":"rs_stream","encrypted_content":"ENC-STREAM"}}""",
        )
        wire.sse(e.messageAdded(OUTPUT_ITEM))
        wire.sse(e.textDelta("answer", OUTPUT_ITEM))
        wire.sse(e.itemDone(OUTPUT_ITEM))
        wire.sse(e.completed("rrs", input = 7, output = 4))
    }

    private fun malformed() {
        wire.sse(e.messageAdded())
        wire.write("data: {not-json}\n\n")
        wire.flush()
        wire.sse(e.textDelta("ok after auth"))
        wire.sse(e.itemDone())
        wire.sse(e.completed("r5", input = 1, output = 1))
    }
}
