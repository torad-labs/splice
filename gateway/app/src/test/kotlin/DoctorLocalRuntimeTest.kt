import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import splice.app.cli.CheckStatus
import splice.app.cli.ConfigHeadWindowOverride
import splice.app.cli.DoctorLocalRuntime
import splice.core.util.EnvReader
import splice.spi.LocalHttp
import splice.spi.LocalHttpReply
import java.nio.file.Path

private const val PING_CALL = "{\"function\":{\"name\":\"ping\"}}"

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
                LocalHttpReply(200, "data: {\"choices\":[{\"delta\":{\"tool_calls\":[$PING_CALL]}}]}\n")
            else -> null
        }
    }

    @Test
    fun `a runtime whose model list does not answer is one WARN row, not a FAIL per model`() {
        val listless = LocalHttp { _, url, _ ->
            if (url.endsWith("/api/version")) LocalHttpReply(200, """{"version":"0.30.5"}""") else null
        }
        val checks = DoctorLocalRuntime(listless).localChecks(TopologyLoader.parse(toml), live = true)
        assertEquals(listOf("local:ollama"), checks.map { it.name })
        assertEquals(CheckStatus.WARN, checks.single().status)
        assertTrue(checks.single().detail.contains("model list does not"), checks.single().detail)
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
        assertEquals(4, live.size, "one live row for the listed model, none for the unlisted one")
        assertEquals(CheckStatus.OK, live.first { it.name == "local:ollama/qwen3:4b/live" }.status)
        assertTrue(live.none { it.name == "local:ollama/ghost:1b/live" })
    }

    /** `doctor --live` loads the model; the verdict must read the window the runtime then reports,
     *  not the list fetched before the load (review 2026-09-14). */
    @Test
    fun `the live probe loads the model and the verdict reads the served window it reveals`() {
        var loaded = false
        val loading = LocalHttp { method, url, body ->
            when {
                url.endsWith("/api/ps") -> {
                    val models = if (loaded) """[{"name":"qwen3:4b","context_length":4096}]""" else "[]"
                    LocalHttpReply(200, """{"models":$models}""")
                }
                url.endsWith("/chat/completions") -> {
                    loaded = true
                    up(method, url, body)
                }
                else -> up(method, url, body)
            }
        }
        val topology = TopologyLoader.parse(toml)
        val cold = DoctorLocalRuntime(loading).localChecks(topology, live = false)
        assertEquals(CheckStatus.OK, cold.first { it.name == "local:ollama/qwen3:4b" }.status, "unloaded: card only")
        val hot = DoctorLocalRuntime(loading).localChecks(topology, live = true)
        val row = hot.first { it.name == "local:ollama/qwen3:4b" }
        assertEquals(CheckStatus.FAIL, row.status, "loaded at 4096 < declared 8192: ${row.detail}")
        assertTrue(row.detail.contains("runtime serves 4096"), row.detail)
    }

    @Test
    fun `doctor applies the daemon's per-head window override in both directions`() {
        // Boot builds catalogFor(head, contextWindowOverride): an override wider than the served window
        // makes the daemon refuse the row, so doctor must FAIL it too; one narrower makes both accept.
        val wide = DoctorLocalRuntime(up, override = { _, _ -> 65536L })
        val widened = wide.localChecks(TopologyLoader.parse(toml), live = false)
        assertEquals(CheckStatus.FAIL, widened.first { it.name == "local:ollama/qwen3:4b" }.status)
        val declared = toml.replace("port = 3901", "port = 3901\ncontext_window = 65536")
        val narrow = DoctorLocalRuntime(up, override = { _, _ -> 8192L })
        val narrowed = narrow.localChecks(TopologyLoader.parse(declared), live = false)
        assertEquals(CheckStatus.OK, narrowed.first { it.name == "local:ollama/qwen3:4b" }.status)
    }

    @Test
    fun `the real config override reads the head's own overrides table like the daemon does`(@TempDir tmp: Path) {
        // Daemon.kt feeds ConfigService BOTH the global layer and [heads.<key>.overrides]; a doctor that
        // built only the global layer said OK for a row boot refuses (REVIEW 2/3 false split).
        val env = EnvReader { name -> tmp.resolve("state").toString().takeIf { name == "CLAUDEX_STATE_DIR" } }
        val widened = toml + "\n[heads.local.overrides]\ncontextWindowOverride = \"65536\"\n"
        val wide = DoctorLocalRuntime(up, env, ConfigHeadWindowOverride(env))
        assertEquals(CheckStatus.FAIL, wide.localChecks(TopologyLoader.parse(widened), live = false).row().status)
        val declared = toml.replace("port = 3901", "port = 3901\ncontext_window = 65536")
        val narrowed = declared + "\n[heads.local.overrides]\ncontextWindowOverride = \"8192\"\n"
        val narrow = DoctorLocalRuntime(up, env, ConfigHeadWindowOverride(env))
        assertEquals(CheckStatus.OK, narrow.localChecks(TopologyLoader.parse(narrowed), live = false).row().status)
        val plain = DoctorLocalRuntime(up, env, ConfigHeadWindowOverride(env))
        assertEquals(CheckStatus.FAIL, plain.localChecks(TopologyLoader.parse(declared), live = false).row().status)
    }

    private fun List<splice.app.cli.DoctorCheck>.row() = first { it.name == "local:ollama/qwen3:4b" }

    @Test
    fun `the head's effective rows are checked, not the provider table`() {
        val overriding = toml.replace("port = 3901", "port = 3901\ncontext_window = 65536")
        val checks = DoctorLocalRuntime(up).localChecks(TopologyLoader.parse(overriding), live = false)
        val row = checks.first { it.name == "local:ollama/qwen3:4b" }
        assertEquals(CheckStatus.FAIL, row.status)
        assertTrue(row.detail.contains("65536"), row.detail)
        val suffixed = toml.replace("id = \"qwen3:4b\"", "id = \"qwen3:4b[8k]\"")
        val stripped = DoctorLocalRuntime(up).localChecks(TopologyLoader.parse(suffixed), live = false)
        assertEquals(CheckStatus.OK, stripped.first { it.name == "local:ollama/qwen3:4b" }.status)
    }

    @Test
    fun `a runtime that is down is one WARN row with the fix, never a fabricated OK`() {
        val checks = DoctorLocalRuntime(http = { _, _, _ -> null }).localChecks(TopologyLoader.parse(toml), live = true)
        assertEquals(1, checks.size)
        assertEquals(CheckStatus.WARN, checks.single().status)
        assertTrue(checks.single().detail.contains("no runtime answering"))
    }
}
