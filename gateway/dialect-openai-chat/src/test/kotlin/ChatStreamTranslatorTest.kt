// NEW: unit test the chat stream machine in isolation (asFlow -> RecordingSink) — reasoning +
// text + tool_calls + finish_reason mapping, truncated + failure paths.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.dialect.chat.ChatStreamTranslator
import splice.dialect.chat.ChatTurnContext
import splice.spi.WireSink

private class Rec : WireSink {
    val calls = mutableListOf<String>()
    val toolOpens = mutableListOf<Pair<String, String>>() // (id, name) — inspect ids without disturbing `calls`
    private var n = 0
    override suspend fun openText() = WireBlockIndex(n++).also { calls.add("openText") }
    override suspend fun openThinking() = WireBlockIndex(n++).also { calls.add("openThinking") }
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(n++).also {
        calls.add("openTool:$name")
        toolOpens.add(id to name)
    }
    override suspend fun textDelta(index: WireBlockIndex, text: String) { calls.add("text:$text") }
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) { calls.add("think:$thinking") }
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) { calls.add("json:$partialJson") }
    override suspend fun closeBlock(index: WireBlockIndex) { calls.add("close") }
    override suspend fun closeAll() { calls.add("closeAll") }
    override suspend fun addTextBlock(text: String) { calls.add("addText:$text") }
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
private fun ctx() = ChatTurnContext({ false }, { null }, 180_000, 900_000)

private fun firedCtx(fired: splice.spi.WatchdogFired?) = ChatTurnContext({ false }, { fired }, 180_000, 900_000)

private suspend fun driveEvents(vararg evs: JsonObject): TurnOutcome =
    ChatStreamTranslator(ctx()).driveTurn(evs.toList().asFlow(), Rec())

class ChatStreamTranslatorTest {

    @Test
    fun `reasoning, text, finish stop`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"reasoning_content":"why"}}]}"""),
                ev("""{"choices":[{"delta":{"content":"Hi "}}]}"""),
                ev("""{"choices":[{"delta":{"content":"there"}}]}"""),
                ev(
                    """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}""",
                ),
            ).asFlow(),
            sink,
        )
        val s = outcome as TurnOutcome.Success
        assertEquals("Hi there", s.bodyText)
        assertEquals("why", s.thinkingText)
        assertEquals(5, s.usage.inputTokens)
        assertTrue(sink.calls.contains("openThinking"))
        assertTrue(sink.calls.contains("text:Hi "))
    }

    @Test
    fun `reasoning alias fields and final message reasoning are captured`() = runTest {
        val sink = Rec()
        // OpenRouter/vLLM-style `reasoning` delta + a final message-shaped frame.
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"reasoning":"step1 "}}]}"""),
                ev("""{"choices":[{"delta":{"thinking":"step2"}}]}"""),
                ev(
                    """{"choices":[{"message":{"role":"assistant","content":"ok","reasoning_content":"final-only"},"finish_reason":"stop"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        val s = outcome as TurnOutcome.Success
        assertEquals("ok", s.bodyText)
        assertTrue(s.thinkingText.contains("step1"))
        assertTrue(s.thinkingText.contains("step2"))
        assertTrue(s.thinkingText.contains("final-only"))
    }

    @Test
    fun `tool_calls stream by index and map to tool_use`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"name":"run","arguments":"{\"a\":"}}]}}]}""",
                ),
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}""",
                ),
                ev("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("openTool:run", "json:{\"a\":", "json:1}", "closeAll"), sink.calls)
    }

    @Test
    fun `tool name deferred to a later delta opens with the real name`() = runTest {
        // Vendors emit index+id first, function.name on a later chunk — opening early freezes "".
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"arguments":""}}]}}]}""",
                ),
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"Read","arguments":"{\"p\":1}"}}]}}]}""",
                ),
                ev("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("openTool:Read", "json:{\"p\":1}", "closeAll"), sink.calls)
    }

    @Test
    fun `final-message tool_calls are harvested when no deltas carried them`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[
                        {"id":"t9","type":"function","function":{"name":"run","arguments":"{\"x\":1}"}}
                    ]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("openTool:run", "json:{\"x\":1}", "closeAll"), sink.calls)
    }

    @Test
    fun `final-message reasoning extends a streamed prefix without duplicating it`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"reasoning_content":"Hello"}}]}"""),
                ev(
                    """{"choices":[{"message":{"role":"assistant","content":"ok",
                        "reasoning_content":"Hello world"},"finish_reason":"stop"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        val s = outcome as TurnOutcome.Success
        assertEquals("Hello world", s.thinkingText)
        assertFalse(s.thinkingText.contains("HelloHello"))
        // Wire-level: the streamed prefix went out, the final fold emits ONLY the unseen suffix,
        // and the full "Hello world" is never re-sent (the buffer alone can't prove this).
        assertTrue(sink.calls.contains("think:Hello"))
        assertTrue(sink.calls.contains("think: world"))
        assertFalse(sink.calls.contains("think:Hello world"))
    }

    @Test
    fun `truncated without finish is overloaded`() = runTest {
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(ev("""{"choices":[{"delta":{"content":"partial"}}]}""")).asFlow(),
            Rec(),
        )
        assertTrue(outcome is TurnOutcome.Failure)
    }

    @Test
    fun `error frame is a failure`() = runTest {
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(ev("""{"error":{"message":"model overloaded"}}""")).asFlow(),
            Rec(),
        )
        val f = outcome as TurnOutcome.Failure
        assertTrue(f.message.contains("model overloaded"))
    }

    // --- real-vendor-shaped fixtures: explicit JSON nulls + prompt-cache tokens (Bug 1 / Bug 2) ---

    @Test
    fun `openai-shaped stream with explicit nulls stays clean and null-free`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"role":"assistant","content":null},"finish_reason":null}]}"""),
                ev("""{"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}"""),
                ev("""{"choices":[{"delta":{"content":" world"},"finish_reason":null}]}"""),
                ev(
                    """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
                ),
            ).asFlow(),
            sink,
        )
        val s = outcome as TurnOutcome.Success
        assertEquals("Hello world", s.bodyText)
        assertFalse(s.bodyText.contains("null"))
        assertFalse(s.hasToolUse)
        assertFalse(s.incomplete)
        // the first-chunk content:null must NOT append literal "null" nor open a text block early
        assertFalse(sink.calls.contains("text:null"))
    }

    @Test
    fun `finish_reason null then truncation is a retryable failure not a clean stop`() = runTest {
        // Every non-final chunk carries finish_reason:null; the stream then dies with NO finish
        // chunk. The null must not trip `finished`, else a truncation masquerades as end_turn (L3).
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"role":"assistant","content":null},"finish_reason":null}]}"""),
                ev("""{"choices":[{"delta":{"content":"partial answer"},"finish_reason":null}]}"""),
            ).asFlow(),
            Rec(),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, f.type)
        assertTrue(f.message.contains("truncated"))
    }

    @Test
    fun `reasoning_content null is ignored but real reasoning opens a thinking block`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"role":"assistant","reasoning_content":null,"content":null},"finish_reason":null}]}""",
                ),
                ev("""{"choices":[{"delta":{"reasoning_content":"because"},"finish_reason":null}]}"""),
                ev("""{"choices":[{"delta":{"content":"Answer"},"finish_reason":null}]}"""),
                ev("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
            ).asFlow(),
            sink,
        )
        val s = outcome as TurnOutcome.Success
        assertEquals("because", s.thinkingText)
        assertEquals("Answer", s.bodyText)
        // exactly one thinking block (the null didn't open one) and it precedes the text block
        assertEquals(1, sink.calls.count { it == "openThinking" })
        assertTrue(sink.calls.indexOf("openThinking") < sink.calls.indexOf("openText"))
    }

    @Test
    fun `usage captures cached tokens from prompt_tokens_details`() = runTest {
        val s = driveEvents(
            ev("""{"choices":[{"delta":{"content":"x"},"finish_reason":null}]}"""),
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":80}}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(100, s.usage.inputTokens)
        assertEquals(5, s.usage.outputTokens)
        assertEquals(80, s.usage.cachedTokens)
    }

    @Test
    fun `usage cached tokens fall back to flat cached_tokens`() = runTest {
        val s = driveEvents(
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5,"cached_tokens":40}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(40, s.usage.cachedTokens)
    }

    @Test
    fun `usage cached tokens fall back to deepseek prompt_cache_hit_tokens`() = runTest {
        val s = driveEvents(
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_cache_hit_tokens":25}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(25, s.usage.cachedTokens)
    }

    @Test
    fun `usage cached tokens absent defaults to zero`() = runTest {
        val s = driveEvents(
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(0, s.usage.cachedTokens)
    }

    @Test
    fun `tool_call with null id falls back to synthetic toolu id not the string null`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{"name":"run","arguments":null}}]},"finish_reason":null}]}""",
                ),
                ev(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]},"finish_reason":null}]}""",
                ),
                ev("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals("toolu_0", sink.toolOpens.single().first)
        // the arguments:null must not leak as a literal "null" input-json delta
        assertFalse(sink.calls.contains("json:null"))
    }

    @Test
    fun `finished turn beats a late watchdog fire - success not overloaded`() = runTest {
        val outcome = ChatStreamTranslator(firedCtx(splice.spi.WatchdogFired.Idle(180_000, true))).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"content":"done"},"finish_reason":null}]}"""),
                ev("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
            ).asFlow(),
            Rec(),
        )
        assertTrue(outcome is TurnOutcome.Success, "finished turn must not be discarded: $outcome")
    }

    @Test
    fun `watchdog fire without a finish stays an overloaded failure`() = runTest {
        val outcome = ChatStreamTranslator(firedCtx(splice.spi.WatchdogFired.Idle(180_000, true))).driveTurn(
            listOf(ev("""{"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}""")).asFlow(),
            Rec(),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, f.type)
    }

    @Test
    fun `content_filter maps to an honest failure, never a clean end_turn`() = runTest {
        val outcome = driveEvents(
            ev("""{"choices":[{"delta":{"content":"redac"},"finish_reason":null}]}"""),
            ev("""{"choices":[{"delta":{},"finish_reason":"content_filter"}]}"""),
        )
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, f.type)
        assertTrue(f.message.contains("content filter"))
    }

    @Test
    fun `index-less parallel tool calls open distinct blocks per id`() = runTest {
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"tool_calls":[""" +
                        """{"id":"call_a","function":{"name":"fn_a","arguments":"{\"x\":1}"}},""" +
                        """{"id":"call_b","function":{"name":"fn_b","arguments":"{\"y\":2}"}}]},""" +
                        """"finish_reason":null}]}""",
                ),
                ev("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""),
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(listOf("call_a" to "fn_a", "call_b" to "fn_b"), sink.toolOpens)
        assertEquals(listOf("json:{\"x\":1}", "json:{\"y\":2}"), sink.calls.filter { it.startsWith("json:") })
    }

    @Test
    fun `streamed tool_calls are not re-applied from a trailing final-message echo`() = runTest {
        // OpenRouter/vLLM-style mixed stream: deltas carry the call (explicit index), then a
        // message-shaped frame echoes the consolidated tool_calls (id, no index). The echo must
        // be a no-op — re-applying it appended the args again onto the open block, or minted a
        // SECOND tool_use for the same id via a fresh synth slot (review 2026-07-22).
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"tool_calls":[""" +
                        """{"index":0,"id":"t1","function":{"name":"run","arguments":"{\"x\":1}"}}]}}]}""",
                ),
                ev(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"t1","type":"function","function":{"name":"run","arguments":"{\"x\":1}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("t1" to "run"), sink.toolOpens)
        assertEquals(listOf("openTool:run", "json:{\"x\":1}", "closeAll"), sink.calls)
    }

    @Test
    fun `a final-only tool call alongside an echo of a streamed call is emitted, not dropped`() = runTest {
        // Superset final message: t1 was streamed (its final echo must be SUPPRESSED) while t2
        // appears ONLY in the consolidated final array (never streamed → must be EMITTED). A
        // turn-global gap-fill flag dropped t2 while still reporting tool_use (review 2026-07-23).
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"tool_calls":[""" +
                        """{"index":0,"id":"t1","function":{"name":"first","arguments":"{}"}}]}}]}""",
                ),
                ev(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"t1","type":"function","function":{"name":"first","arguments":"{}"}},""" +
                        """{"id":"t2","type":"function","function":{"name":"second","arguments":"{\"x\":1}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        // t1 opened once (echo suppressed), t2 opened once (final-only, emitted) — neither dropped.
        assertEquals(listOf("t1" to "first", "t2" to "second"), sink.toolOpens)
        assertEquals(1, sink.calls.count { it == "openTool:first" })
        assertEquals(1, sink.calls.count { it == "openTool:second" })
    }

    @Test
    fun `final-message name completes a nameless pending tool without duplicating args`() = runTest {
        // A backend streams the tool's arguments but never function.name on any delta, then supplies
        // the name only in the trailing consolidated message. The pending slot must adopt that name
        // (not flush under the "tool" fallback), and the echo's arguments must NOT be appended a
        // second time onto the buffered slot (review 2026-07-22 round 3).
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev(
                    """{"choices":[{"delta":{"tool_calls":[""" +
                        """{"index":0,"id":"t1","function":{"arguments":"{\"x\":1}"}}]}}]}""",
                ),
                ev(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[""" +
                        """{"id":"t1","type":"function","function":{"name":"run","arguments":"{\"x\":1}"}}""" +
                        """]},"finish_reason":"tool_calls"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("t1" to "run"), sink.toolOpens)
        assertEquals(1, sink.calls.count { it == "openTool:run" })
        assertEquals(listOf("json:{\"x\":1}"), sink.calls.filter { it.startsWith("json:") })
    }

    @Test
    fun `final-message content extends a streamed prefix without duplicating or dropping the tail`() = runTest {
        // Sibling of the reasoning prefix-fold: streamed "Hello", final message completes to
        // "Hello world" — the old isEmpty() guard dropped " world" entirely.
        val sink = Rec()
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"content":"Hello"}}]}"""),
                ev(
                    """{"choices":[{"message":{"role":"assistant","content":"Hello world"},""" +
                        """"finish_reason":"stop"}]}""",
                ),
            ).asFlow(),
            sink,
        )
        val s = outcome as TurnOutcome.Success
        assertEquals("Hello world", s.bodyText)
        assertTrue(sink.calls.contains("text:Hello"))
        assertTrue(sink.calls.contains("text: world"))
        assertFalse(s.bodyText.contains("HelloHello"))
    }

    @Test
    fun `finish_reason max_tokens marks the turn incomplete like the standard length`() = runTest {
        // OpenAI standard is "length"; several OpenAI-compat vendors emit "max_tokens". Both must
        // set incomplete so the Anthropic terminal is max_tokens, not a clean end_turn.
        val outcome = ChatStreamTranslator(ctx()).driveTurn(
            listOf(
                ev("""{"choices":[{"delta":{"content":"partial answer"}}]}"""),
                ev("""{"choices":[{"delta":{},"finish_reason":"max_tokens"}]}"""),
            ).asFlow(),
            Rec(),
        )
        val s = outcome as TurnOutcome.Success
        assertTrue(s.incomplete, "max_tokens must mark the turn incomplete")
        assertFalse(s.hasToolUse)
    }
}
