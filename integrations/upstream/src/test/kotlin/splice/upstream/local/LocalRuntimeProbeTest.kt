// NEW: moved from splice.app.provider.local with the probe it pins (V4-103).
package splice.upstream.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply

/** models() is null only when the list call fails; these routes always answer it. */
private fun LocalRuntimeProbe.listed(runtime: LocalRuntime) = checkNotNull(models(runtime))

class LocalRuntimeProbeTest {

    private fun http(routes: Map<String, String>) = LocalHttp { method, url, body ->
        val key = "$method ${url.substringAfter("http://localhost:1")}"
        routes[key]?.let { LocalHttpReply(200, it) }
    }

    private val ollama = http(
        mapOf(
            "GET /api/version" to """{"version":"0.30.5"}""",
            "GET /v1/models" to """{"object":"list","data":[{"id":"qwen3:4b","object":"model"}]}""",
            "POST /api/show" to
                """{"parameters":"num_ctx 8192\nstop \"<|im_end|>\"",""" +
                """"model_info":{"general.architecture":"qwen3","qwen3.context_length":40960}}""",
        ),
    )

    @Test
    fun `a list call that does not answer is null, never an empty list, and only bounds the show reads`() {
        val shown = mutableListOf<String>()
        val base = mapOf("GET http://h/api/version" to """{"version":"0.30.5"}""")
        val listless = LocalRuntimeProbe("http://h/v1", http(base))
        val runtime = checkNotNull(listless.detect())
        assertNull(listless.models(runtime), "the list did not answer: not the same as listing nothing")
        val routes = base + ("GET http://h/v1/models" to """{"data":[{"id":"a"},{"id":"b"},{"id":"c"}]}""") +
            ("GET http://h/api/ps" to """{"models":[]}""")
        val counting = LocalHttp { method, url, body ->
            if (url.endsWith("/api/show")) shown += body.orEmpty()
            http(routes)(method, url, body)
        }
        val bounded = LocalRuntimeProbe("http://h/v1", counting)
        val listed = checkNotNull(bounded.models(runtime, setOf("b")))
        assertEquals(listOf("a", "b", "c"), listed.map { it.id }, "every id is still listed")
        assertEquals(1, shown.size, "one /api/show, for the row that is validated: $shown")
        assertTrue(shown.single().contains("\"b\""), shown.single())
    }

    @Test
    fun `Ollama is detected with its version, models and the effective num_ctx`() {
        val probe = LocalRuntimeProbe("http://localhost:1/v1", ollama)
        val runtime = checkNotNull(probe.detect())
        assertEquals(LocalRuntimeKind.OLLAMA, runtime.kind)
        assertEquals("0.30.5", runtime.version)
        val models = probe.listed(runtime)
        assertEquals("qwen3:4b", models.single().id)
        assertEquals(8192L, models.single().contextLength)
        assertTrue(models.single().detail!!.contains("40960"))
    }

    @Test
    fun `a row is refused when unlisted or over the runtime's context, accepted otherwise`() {
        val probe = LocalRuntimeProbe("http://localhost:1/v1", ollama)
        val listed = probe.listed(checkNotNull(probe.detect()))
        val verdicts = probe.validate(mapOf("qwen3:4b" to 8192L, "qwen3:8b" to 1000L, "big" to 1L), listed)
            .associateBy { it.id }
        assertTrue(verdicts.getValue("qwen3:4b").ok)
        assertFalse(verdicts.getValue("qwen3:8b").ok)
        assertTrue(verdicts.getValue("qwen3:8b").reason.contains("not listed"))
        val over = probe.validate(mapOf("qwen3:4b" to 32768L), listed).single()
        assertFalse(over.ok)
        assertTrue(over.reason.contains("runtime serves 8192"), over.reason)
    }

    @Test
    fun `an untagged id is its latest tag, and a generic server's list never refuses a row - review 2026-09-14`() {
        val probe = LocalRuntimeProbe("http://localhost:1/v1", ollama)
        val latest = listOf(LocalModel("qwen3:latest", 8192L))
        val tagged = probe.validate(mapOf("qwen3" to 8192L, "qwen3:latest" to 8192L), latest).associateBy { it.id }
        val untagged = tagged.getValue("qwen3")
        assertTrue(untagged.ok, "Ollama lists qwen3:latest and serves qwen3: $untagged")
        assertTrue(tagged.getValue("qwen3:latest").ok)
        assertFalse(probe.validate(mapOf("qwen3" to 100000L), latest).single().ok, "the alias still carries its window")
        val proxy = listOf(LocalModel("alias-a", null))
        val generic = probe.validate(mapOf("gpt-x" to 128000L), proxy, LocalRuntimeKind.OPENAI_COMPATIBLE).single()
        assertTrue(generic.ok, generic.reason)
        assertTrue(generic.reason.contains("not authoritative"), generic.reason)
        val vllm = probe.validate(mapOf("gpt-x" to 128000L), proxy, LocalRuntimeKind.VLLM).single()
        assertFalse(vllm.ok, "vLLM lists all it serves")
    }

    @Test
    fun `without num_ctx the card is only a ceiling until Ollama reports the loaded window`() {
        val show = """{"parameters":"stop \"<|im_end|>\"",""" +
            """"model_info":{"general.architecture":"qwen3","qwen3.context_length":262144}}"""
        val base = mapOf(
            "GET /api/version" to """{"version":"0.30.5"}""",
            "GET /v1/models" to """{"data":[{"id":"qwen3:4b"}]}""",
            "POST /api/show" to show,
        )
        val unloaded = LocalRuntimeProbe("http://localhost:1/v1", http(base))
        val cold = unloaded.listed(checkNotNull(unloaded.detect())).single()
        assertNull(cold.contextLength)
        assertEquals(262144L, cold.ceiling)
        val rows = mapOf("qwen3:4b" to 65536L)
        assertTrue(unloaded.validate(rows, listOf(cold)).single().ok)
        assertFalse(unloaded.validate(mapOf("qwen3:4b" to 300000L), listOf(cold)).single().ok)

        val ps = """{"models":[{"name":"qwen3:4b","context_length":32768}]}"""
        val loaded = LocalRuntimeProbe("http://localhost:1/v1", http(base + ("GET /api/ps" to ps)))
        val warm = loaded.listed(checkNotNull(loaded.detect())).single()
        assertEquals(32768L, warm.contextLength)
        val over = loaded.validate(rows, listOf(warm)).single()
        assertFalse(over.ok)
        assertTrue(over.reason.contains("serves 32768"), over.reason)
    }

    @Test
    fun `LM Studio answering 200 with an error object on Ollama's paths is still LM Studio`() {
        val lmStudio = LocalHttp { _, url, _ ->
            if (url.endsWith("/api/v0/models")) {
                val row = """{"id":"qwen/qwen3-4b","max_context_length":32768,"state":"loaded","loaded_context_length":8192}"""
                LocalHttpReply(200, """{"data":[$row],"object":"list"}""")
            } else {
                LocalHttpReply(200, """{"error":"Unexpected endpoint or method. (GET ${url.substringAfter(":1")})"}""")
            }
        }
        val probe = LocalRuntimeProbe("http://localhost:1/v1", lmStudio)
        val runtime = checkNotNull(probe.detect())
        assertEquals(LocalRuntimeKind.LM_STUDIO, runtime.kind)
        val model = probe.listed(runtime).single()
        assertEquals(8192L, model.contextLength)
        assertFalse(probe.validate(mapOf("qwen/qwen3-4b" to 32768L), listOf(model)).single().ok)
    }

    @Test
    fun `the loaded window beats a modelfile num_ctx that claims more`() {
        val show = """{"parameters":"num_ctx 65536","model_info":{"qwen3.context_length":262144}}"""
        val ps = """{"models":[{"name":"qwen3:4b","context_length":32768}]}"""
        val probe = LocalRuntimeProbe(
            "http://localhost:1/v1",
            http(
                mapOf(
                    "GET /api/version" to """{"version":"0.30.5"}""",
                    "GET /v1/models" to """{"data":[{"id":"qwen3:4b"}]}""",
                    "POST /api/show" to show,
                    "GET /api/ps" to ps,
                ),
            ),
        )
        val warm = probe.listed(checkNotNull(probe.detect())).single()
        assertEquals(32768L, warm.contextLength)
        assertFalse(probe.validate(mapOf("qwen3:4b" to 65536L), listOf(warm)).single().ok)
    }

    @Test
    fun `LM Studio and vLLM report context their own way and an unknown runtime reports none`() {
        val lmstudio = LocalRuntimeProbe(
            "http://localhost:1/v1",
            http(
                mapOf(
                    "GET /api/v0/models" to
                        """{"data":[{"id":"gemma-3-4b","max_context_length":131072,""" +
                        """"loaded_context_length":4096,"state":"loaded"}]}""",
                ),
            ),
        )
        val lm = checkNotNull(lmstudio.detect())
        assertEquals(LocalRuntimeKind.LM_STUDIO, lm.kind)
        assertEquals(4096L, lmstudio.listed(lm).single().contextLength)

        val vllm = LocalRuntimeProbe(
            "http://localhost:1/v1",
            http(mapOf("GET /v1/models" to """{"data":[{"id":"meta-llama/Llama-3.1-8B","max_model_len":32768}]}""")),
        )
        val v = checkNotNull(vllm.detect())
        assertEquals(LocalRuntimeKind.VLLM, v.kind)
        assertEquals(32768L, vllm.listed(v).single().contextLength)

        val generic = LocalRuntimeProbe(
            "http://localhost:1/v1",
            http(mapOf("GET /v1/models" to """{"data":[{"id":"m"}]}""")),
        )
        val g = checkNotNull(generic.detect())
        assertEquals(LocalRuntimeKind.OPENAI_COMPATIBLE, g.kind)
        assertNull(generic.listed(g).single().contextLength)
        assertTrue(generic.validate(mapOf("m" to 999999L), generic.listed(g)).single().ok)
        assertNull(LocalRuntimeProbe("http://localhost:1/v1", http(emptyMap())).detect())
    }

    @Test
    fun `a server answering the OpenAI list on every models path is generic, not LM Studio`() {
        val list = """{"object":"list","data":[{"id":"m","object":"model"}]}"""
        val lenient = LocalHttp { _, url, _ -> if (url.endsWith("/models")) LocalHttpReply(200, list) else null }
        val probe = LocalRuntimeProbe("http://localhost:1/v1", lenient)
        val runtime = checkNotNull(probe.detect())
        assertEquals(LocalRuntimeKind.OPENAI_COMPATIBLE, runtime.kind)
        assertNull(probe.listed(runtime).single().contextLength)
    }

    @Test
    fun `the live probe reads streaming and tool calls from the reply`() {
        val streamed = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"ping\"}}]}}]}\n\n" +
            "data: [DONE]\n"
        val probe = LocalRuntimeProbe(
            "http://localhost:1/v1",
            http(mapOf("POST /v1/chat/completions" to streamed)),
        )
        val live = probe.live("m")
        assertTrue(live.streams && live.toolCalls, live.detail)
        val plain = LocalRuntimeProbe(
            "http://localhost:1/v1",
            http(mapOf("POST /v1/chat/completions" to """{"choices":[{"message":{"content":"pong"}}]}""")),
        )
        val p = plain.live("m")
        assertFalse(p.streams || p.toolCalls, p.detail)
        assertFalse(LocalRuntimeProbe("http://localhost:1/v1", http(emptyMap())).live("m").streams)
    }

    @Test
    fun `a tool call is proven by shape, and a non-200 reply shows its status class, never its body`() {
        val lying = "data: {\"error\":{\"message\":\"tool_calls are not supported; ping ignored\"}}\n\ndata: [DONE]\n"
        val error = LocalRuntimeProbe("http://localhost:1/v1", http(mapOf("POST /v1/chat/completions" to lying)))
            .live("m")
        assertTrue(error.streams, error.detail)
        assertFalse(error.toolCalls, "an error chunk naming tool_calls and ping is not a call: " + error.detail)
        val other = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"pong\"}}]}}]}\n"
        assertFalse(
            LocalRuntimeProbe("http://localhost:1/v1", http(mapOf("POST /v1/chat/completions" to other))).live("m")
                .toolCalls,
            "a call to another tool is not the requested call",
        )
        val plain = """{"choices":[{"message":{"tool_calls":[{"function":{"name":"ping","arguments":"{}"}}]}}]}"""
        val unstreamed = LocalRuntimeProbe("http://localhost:1/v1", http(mapOf("POST /v1/chat/completions" to plain)))
            .live("m")
        assertTrue(unstreamed.toolCalls && !unstreamed.streams, unstreamed.detail)
        val secret = "Authorization: Bearer sk-live-SECRET /home/user/private \u001b[31m"
        val refused = LocalRuntimeProbe(
            "http://localhost:1/v1",
            LocalHttp { _, _, _ -> LocalHttpReply(401, """{"error":"$secret"}""") },
        ).live("m")
        assertFalse(refused.streams || refused.toolCalls)
        assertTrue(refused.detail.startsWith("HTTP 401 (credential refused"), refused.detail)
        assertFalse(refused.detail.contains("SECRET") || refused.detail.contains("private"), refused.detail)
        assertFalse(refused.detail.contains("\u001b"), refused.detail)
    }
}
