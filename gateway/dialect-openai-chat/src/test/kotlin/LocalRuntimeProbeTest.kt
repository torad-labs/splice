import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.dialect.chat.LocalHttp
import splice.dialect.chat.LocalHttpReply
import splice.dialect.chat.LocalRuntimeKind
import splice.dialect.chat.LocalRuntimeProbe

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
    fun `Ollama is detected with its version, models and the effective num_ctx`() {
        val probe = LocalRuntimeProbe("http://localhost:1/v1", ollama)
        val runtime = checkNotNull(probe.detect())
        assertEquals(LocalRuntimeKind.OLLAMA, runtime.kind)
        assertEquals("0.30.5", runtime.version)
        val models = probe.models(runtime)
        assertEquals("qwen3:4b", models.single().id)
        assertEquals(8192L, models.single().contextLength)
        assertTrue(models.single().detail!!.contains("40960"))
    }

    @Test
    fun `a row is refused when unlisted or over the runtime's context, accepted otherwise`() {
        val probe = LocalRuntimeProbe("http://localhost:1/v1", ollama)
        val listed = probe.models(checkNotNull(probe.detect()))
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
    fun `without num_ctx the card is only a ceiling until Ollama reports the loaded window`() {
        val show = """{"parameters":"stop \"<|im_end|>\"",""" +
            """"model_info":{"general.architecture":"qwen3","qwen3.context_length":262144}}"""
        val base = mapOf(
            "GET /api/version" to """{"version":"0.30.5"}""",
            "GET /v1/models" to """{"data":[{"id":"qwen3:4b"}]}""",
            "POST /api/show" to show,
        )
        val unloaded = LocalRuntimeProbe("http://localhost:1/v1", http(base))
        val cold = unloaded.models(checkNotNull(unloaded.detect())).single()
        assertNull(cold.contextLength)
        assertEquals(262144L, cold.ceiling)
        val rows = mapOf("qwen3:4b" to 65536L)
        assertTrue(unloaded.validate(rows, listOf(cold)).single().ok)
        assertFalse(unloaded.validate(mapOf("qwen3:4b" to 300000L), listOf(cold)).single().ok)

        val ps = """{"models":[{"name":"qwen3:4b","context_length":32768}]}"""
        val loaded = LocalRuntimeProbe("http://localhost:1/v1", http(base + ("GET /api/ps" to ps)))
        val warm = loaded.models(checkNotNull(loaded.detect())).single()
        assertEquals(32768L, warm.contextLength)
        val over = loaded.validate(rows, listOf(warm)).single()
        assertFalse(over.ok)
        assertTrue(over.reason.contains("serves 32768"), over.reason)
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
        assertEquals(4096L, lmstudio.models(lm).single().contextLength)

        val vllm = LocalRuntimeProbe(
            "http://localhost:1/v1",
            http(mapOf("GET /v1/models" to """{"data":[{"id":"meta-llama/Llama-3.1-8B","max_model_len":32768}]}""")),
        )
        val v = checkNotNull(vllm.detect())
        assertEquals(LocalRuntimeKind.VLLM, v.kind)
        assertEquals(32768L, vllm.models(v).single().contextLength)

        val generic = LocalRuntimeProbe(
            "http://localhost:1/v1",
            http(mapOf("GET /v1/models" to """{"data":[{"id":"m"}]}""")),
        )
        val g = checkNotNull(generic.detect())
        assertEquals(LocalRuntimeKind.OPENAI_COMPATIBLE, g.kind)
        assertNull(generic.models(g).single().contextLength)
        assertTrue(generic.validate(mapOf("m" to 999999L), generic.models(g)).single().ok)
        assertNull(LocalRuntimeProbe("http://localhost:1/v1", http(emptyMap())).detect())
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
}
