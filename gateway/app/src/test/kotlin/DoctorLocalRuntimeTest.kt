import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.TopologyLoader
import splice.app.cli.CheckStatus
import splice.app.cli.DoctorLocalRuntime
import splice.dialect.chat.LocalHttp
import splice.dialect.chat.LocalHttpReply

class DoctorLocalRuntimeTest {

    private val toml = """
        [providers.ollama]
        dialect = "openai-chat"
        base_url = "http://localhost:11434/v1"
        auth = { kind = "api-key", env = "NONE" }
        [[providers.ollama.models]]
        id = "qwen3:4b"
        context_window = 8192
        [[providers.ollama.models]]
        id = "ghost:1b"
        context_window = 4096
        [providers.cloud]
        dialect = "openai-chat"
        base_url = "https://openrouter.ai/api/v1"
        auth = { kind = "api-key", env = "K" }
        [heads.local]
        provider = "ollama"
        port = 3901
        discovery_prefix = "claude-local--"
        pinned_model = "qwen3:4b"
    """.trimIndent()

    private val up = LocalHttp { method, url, _ ->
        when {
            url.endsWith("/api/version") -> LocalHttpReply(200, """{"version":"0.30.5"}""")
            url.endsWith("/v1/models") -> LocalHttpReply(200, """{"data":[{"id":"qwen3:4b"}]}""")
            url.endsWith("/api/show") && method == "POST" ->
                LocalHttpReply(200, """{"model_info":{"qwen3.context_length":40960}}""")
            url.endsWith("/chat/completions") ->
                LocalHttpReply(200, "data: {\"tool_calls\":[{\"function\":{\"name\":\"ping\"}}]}\n")
            else -> null
        }
    }

    @Test
    fun `only local providers are checked, rows answer for themselves, live rows appear only on request`() {
        val topology = TopologyLoader.parse(toml)
        val checks = DoctorLocalRuntime(up).localChecks(topology, live = false)
        assertEquals(listOf("local:ollama", "local:ollama/qwen3:4b", "local:ollama/ghost:1b"), checks.map { it.name })
        assertTrue(checks[0].detail.contains("Ollama 0.30.5"), checks[0].detail)
        assertEquals(CheckStatus.OK, checks[1].status)
        assertEquals(CheckStatus.FAIL, checks[2].status)
        assertTrue(checks[2].detail.contains("not listed"))
        val live = DoctorLocalRuntime(up).localChecks(topology, live = true)
        assertEquals(5, live.size)
        assertEquals(CheckStatus.OK, live.first { it.name == "local:ollama/qwen3:4b/live" }.status)
    }

    @Test
    fun `a runtime that is down is one WARN row with the fix, never a fabricated OK`() {
        val checks = DoctorLocalRuntime { _, _, _ -> null }.localChecks(TopologyLoader.parse(toml), live = true)
        assertEquals(1, checks.size)
        assertEquals(CheckStatus.WARN, checks.single().status)
        assertTrue(checks.single().detail.contains("no runtime answering"))
    }
}
