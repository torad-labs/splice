// NEW (v0.4.0, FEATURES.md §6): `splice doctor --json` is an allowlist — synthetic secrets and
// conversation text planted in the config, the perf tail and the daemon log must not reach the
// output, the output must parse, and an injected failing check must be represented.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import splice.app.cli.CheckStatus
import splice.app.cli.DoctorCheck
import splice.app.cli.DoctorCommand
import splice.app.cli.DoctorReport
import splice.app.cli.DoctorReportOptions
import splice.app.cli.DoctorRun
import splice.core.config.StatePaths
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class DoctorReportTest {

    @TempDir
    lateinit var tmp: Path

    private val secrets = listOf(
        "BEARER-SECRET-VALUE-0001",
        "user:hunter2pw@",
        "sk-live-abcdefghijklmnop",
        "ops@example.com",
        "PLANTEXT the user's secret plan",
        "/home/operator/projects/private-repo",
        "sess-identifier-1234",
    )

    private val toml = """
        [daemon]
        control_port = 3999
        [providers.codex]
        dialect = "openai-responses"
        base_url = "https://user:hunter2pw@chatgpt.example.invalid/backend-api/codex"
        auth = { kind = "chatgpt-oauth" }
        extra_headers = { "X-Team" = "BEARER-SECRET-VALUE-0001" }
        [[providers.codex.models]]
        id = "gpt-6-astra"
        context_window = 272000
        [heads.codex]
        provider = "codex"
        port = 3998
        discovery_prefix = "claudex--"
        pinned_model = "gpt-6-astra"
    """.trimIndent()

    private fun plant(): Map<String, String?> {
        val config = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(config.resolve("splice.toml"), toml)
        val state = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(state.resolve("mgmt-key"), "mgmt-secret-key-value-abcdef\n")
        Files.writeString(
            state.resolve("codex-perf.jsonl"),
            """{"ts":1,"model":"gpt-6-astra","outcome":"ok","compact":false,"session":"sess-identifier-1234","prompt":"PLANTEXT the user's secret plan","total":42,"out_tokens":7}""" +
                "\n" + """{"ts":2,"model":"gpt-6-astra","outcome":"client_abort","compact":true,"cwd":"/home/operator/projects/private-repo","total":9}""" + "\n",
        )
        val logs = Files.createDirectories(tmp.resolve("logs"))
        Files.writeString(
            logs.resolve("daemon.log"),
            "[2026-09-13 10:00:52] [daemon] up: control :3999, heads [codex]\n" +
                "[2026-09-13 10:00:53] [codex] refresh with Bearer BEARER-SECRET-VALUE-0001 for ops@example.com key sk-live-abcdefghijklmnop\n" +
                "PLANTEXT the user's secret plan\n" +
                "[2026-09-13 10:00:54] [codex] cwd /home/operator/projects/private-repo\n" +
                "[2026-09-13 10:00:55] [codex] cwd ${System.getProperty("user.home")}/projects/real-private\n",
        )
        return mapOf(
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
            "CLAUDEX_STATE_DIR" to state.toString(),
            "SPLICE_BIN_DIR" to tmp.resolve("bin").toString(),
            "SPLICE_SHARE_DIR" to tmp.resolve("share").toString(),
            "PATH" to tmp.resolve("bin").toString(),
            "SPLICE_CONTROL_PORT" to "3999",
        )
    }

    private fun report(env: Map<String, String?>, withLogs: Boolean, run: DoctorRun? = null): JsonObject {
        val topology = TopologyLoader.parse(toml)
        val given = run
            ?: DoctorRun(topology, listOf("configuration" to listOf(DoctorCheck("topology", CheckStatus.OK, "ok"))))
        return DoctorReport(
            envReader = { env[it] },
            claudeVersion = { "2.1.257 (Claude Code) for ops@example.com" },
            home = java.nio.file.Paths.get("/home/operator"),
            statePaths = StatePaths(envReader = { env[it] }),
        ).build(given, withLogs)
    }

    @Test
    fun `planted secrets and conversation text never reach the report, with or without logs`() {
        val env = plant()
        for (withLogs in listOf(false, true)) {
            val text = report(env, withLogs).toString()
            secrets.forEach { assertFalse(text.contains(it), "$it leaked (withLogs=$withLogs): $text") }
            assertTrue(text.contains("chatgpt.example.invalid"), text)
        }
    }

    @Test
    fun `the topology shape, perf allowlist and log tail carry only named fields`() {
        val out = report(plant(), withLogs = true)
        assertEquals(1, out.getValue("schema_version").jsonPrimitive.content.toInt())
        val provider = out.getValue("topology").jsonObject.getValue("providers").jsonObject.getValue("codex").jsonObject
        assertEquals("chatgpt-oauth", provider.getValue("auth_kind").jsonPrimitive.content)
        assertEquals("true", provider.getValue("quirks").jsonObject.getValue("code_mode").jsonPrimitive.content)
        assertEquals("1", provider.getValue("extra_headers").jsonPrimitive.content)
        val rows = out.getValue("perf").jsonObject.getValue("codex").jsonArray
        assertEquals(2, rows.size)
        assertEquals(setOf("ts", "model", "outcome", "compact", "total", "out_tokens"), rows[0].jsonObject.keys)
        assertEquals(setOf("ts", "model", "outcome", "compact", "total"), rows[1].jsonObject.keys)
        val logs = out.getValue("logs").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(4, logs.size)
        assertTrue(logs[1].contains("Bearer <redacted>"), logs[1])
        assertTrue(logs[2].endsWith("cwd ~/projects/private-repo"), logs[2])
        val claude = out.getValue("claude_code").jsonObject.getValue("version").jsonPrimitive.content
        assertTrue(claude.startsWith("2.1.257"), claude)
    }

    @Test
    fun `an injected failing check is represented and drives the exit verdict`() {
        val env = plant()
        val failing = DoctorRun(
            TopologyLoader.parse(toml),
            listOf(
                "auth" to listOf(
                    DoctorCheck(
                        "codex",
                        CheckStatus.FAIL,
                        "token at /home/operator/.config/splice/auth/codex.json expired",
                        "splice login codex",
                    ),
                ),
            ),
        )
        val out = report(env, withLogs = false, run = failing)
        val check = out.getValue("checks").jsonArray.single().jsonObject
        assertEquals("auth/codex", check.getValue("id").jsonPrimitive.content)
        assertEquals("fail", check.getValue("status").jsonPrimitive.content)
        assertTrue(check.getValue("detail").jsonPrimitive.content.startsWith("token at ~/.config/splice"))
        assertEquals("splice login codex", check.getValue("fix").jsonPrimitive.content)
        val quiet = captureStdout {
            DoctorReport(
                envReader = { env[it] },
                claudeVersion = { "x" },
                statePaths = StatePaths(envReader = { env[it] }),
            ).emit(failing, DoctorReportOptions(json = true, withLogs = false, out = null))
        }
        assertFalse(quiet.first)
        val emitted = Json.parseToJsonElement(quiet.second).jsonObject.getValue("checks").jsonArray.single().jsonObject
        assertEquals("fail", emitted.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `the real doctor run emits parseable JSON under --json and writes --out after showing the logs`() {
        val env = plant()
        val (ok, printed) = captureStdout { DoctorCommand().doctor(listOf("--json")) { env[it] } }
        val parsed = Json.parseToJsonElement(printed).jsonObject
        assertEquals(1, parsed.getValue("schema_version").jsonPrimitive.content.toInt())
        assertTrue(parsed.getValue("checks").jsonArray.isNotEmpty())
        assertFalse(printed.contains("BEARER-SECRET-VALUE-0001"))
        assertTrue(parsed["logs"] == null)
        val failed = parsed.getValue("checks").jsonArray.any {
            it.jsonObject.getValue("status").jsonPrimitive.content == "fail"
        }
        assertEquals(ok, !failed)

        val out = tmp.resolve("report.json")
        val (_, shown) = captureStdout {
            DoctorCommand().doctor(listOf("--json", "--with-logs", "--out", out.toString())) { env[it] }
        }
        assertTrue(shown.contains("leaving the machine"), shown)
        assertTrue(shown.contains("Bearer <redacted>"), shown)
        val written = Json.parseToJsonElement(Files.readString(out)).jsonObject
        val logs = (written.getValue("logs") as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(4, logs.size)
        // The real run redacts the REAL home: the fake-home path is just a path here, the real one is ~.
        assertTrue(logs[3].endsWith("cwd ~/projects/real-private"), logs[3])
        secrets.filterNot { it.startsWith("/home/operator") }
            .forEach { assertFalse(Files.readString(out).contains(it), "$it leaked into --out") }
    }

    private fun captureStdout(block: () -> Boolean): Pair<Boolean, String> {
        val buf = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buf, true))
        return try {
            block() to buf.toString()
        } finally {
            System.setOut(original)
        }
    }
}
