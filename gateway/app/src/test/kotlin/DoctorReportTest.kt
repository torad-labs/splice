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
            """{"ts":1,"model":"gpt-6-astra","outcome":"ok","compact":false,"cache_cold":"true","account":"ops team PLANTEXT","session":"sess-identifier-1234","prompt":"PLANTEXT the user's secret plan","total":42,"out_tokens":7}""" +
                "\n" + """{"ts":2,"model":"gpt-6-astra","outcome":"client_abort","compact":true,"cache_cold":false,"account":"work","cwd":"/home/operator/projects/private-repo","total":9}""" +
                "\n" + """{"ts":3,"model":"gpt-6-astra","outcome":"PLANTEXT the user's secret plan as an outcome","total":9}""" + "\n",
        )
        val logs = Files.createDirectories(tmp.resolve("logs"))
        Files.writeString(
            logs.resolve("daemon.log"),
            "[2026-09-13 10:00:52] [daemon] up: control :3999, heads [codex]\n" +
                "[2026-09-13 10:00:53] [codex] turn ERROR auth-missing: refresh with Bearer BEARER-SECRET-VALUE-0001 " +
                "for ops@example.com key sk-live-abcdefghijklmnop\n" +
                "PLANTEXT the user's secret plan\n" +
                "[2026-09-13 10:00:53] [daemon] PLANTEXT the user's secret plan behind a daemon prefix\n" +
                "[2026-09-13 10:00:54] [codex] client gone (session 1) cwd /home/operator/projects/private-repo\n" +
                "[2026-09-13 10:00:55] [codex] launch claudex -> cwd " +
                "${System.getProperty("user.home")}/projects/real-private\n",
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

    @Test
    fun `the accounts block carries labels, flags, windows and the switch, keyed by head`() {
        val env = plant()
        val view = splice.app.cli.AccountPoolProjection().parse(
            """{"codex":{"account_pool":{"selected_label":"work","accounts":[""" +
                """{"label":"primary","primary":true,"selected":false,"available":false,"plan":"plus",""" +
                """"five_hour_used_percent":100.0,"seven_day_used_percent":61.5,""" +
                """"auth":{"account_id":"acct-SECRET","email":"ops@example.com"}},""" +
                """{"label":"work","primary":false,"selected":true,"available":true}],""" +
                """"last_switch":{"from":"primary","to":"work","reason":"7d window exhausted","at_epoch_millis":7}}}}""",
        )
        val run = DoctorRun(TopologyLoader.parse(toml), emptyList(), view)
        val text = Json.encodeToString(JsonObject.serializer(), report(env, withLogs = false, run = run))
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("example.com"))
        val accounts = Json.parseToJsonElement(text).jsonObject.getValue("accounts").jsonObject
        val codex = accounts.getValue("codex").jsonObject
        assertEquals("work", codex.getValue("selected").jsonPrimitive.content)
        val labels = codex.getValue("accounts").jsonArray.map { it.jsonObject.getValue("label").jsonPrimitive.content }
        assertEquals(listOf("primary", "work"), labels)
        val switch = codex.getValue("last_switch").jsonObject
        assertEquals("7d window exhausted", switch.getValue("reason").jsonPrimitive.content)
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
        val perf = out.getValue("perf").jsonObject.getValue("codex").jsonObject
        assertEquals(setOf("rows"), perf.keys, "no read error on a readable file")
        val rows = perf.getValue("rows").jsonArray
        assertEquals(3, rows.size)
        assertEquals(setOf("model", "outcome", "compact", "total", "out_tokens"), rows[0].jsonObject.keys)
        assertEquals(setOf("model", "outcome", "compact", "cache_cold", "account", "total"), rows[1].jsonObject.keys)
        assertEquals("false", rows[1].jsonObject.getValue("cache_cold").jsonPrimitive.content, "a JSON boolean only")
        assertEquals(setOf("model", "total"), rows[2].jsonObject.keys, "a prose outcome is not a token: dropped")
        val logs = out.getValue("logs").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(4, logs.size)
        assertEquals(2, out.getValue("logs_dropped_in_tail").jsonPrimitive.content.toInt(), "prose, prefixed or not")
        assertEquals("[2026-09-13 10:00:53] [codex] turn ERROR", logs[1], "structure only: no words after the head")
        assertEquals("[2026-09-13 10:00:54] [codex] client gone", logs[2], "a cwd is a free-form suffix: gone")
        assertTrue(out["logs_error"] == null)
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
        assertEquals(setOf("id", "status", "detail"), check.keys, "schema 1: a check is exactly these three")
        assertEquals("auth/codex", check.getValue("id").jsonPrimitive.content)
        assertEquals("fail", check.getValue("status").jsonPrimitive.content)
        val detail = check.getValue("detail").jsonPrimitive.content
        assertTrue(detail.startsWith("token at ~/.config/splice/auth/codex.json expired"), detail)
        assertTrue(detail.endsWith("fix: splice login codex"), "the fix rides inside the detail: $detail")
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
        assertFalse(printed.contains("as an outcome"), "a prose perf outcome reaches no check detail: $printed")
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
        assertTrue(shown.contains("[codex] turn ERROR"), shown)
        val written = Json.parseToJsonElement(Files.readString(out)).jsonObject
        val logs = (written.getValue("logs") as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(4, logs.size)
        // The launch line's cwd is a free-form suffix: the real home's project never leaves.
        assertTrue(logs[3].endsWith("[codex] launch"), logs[3])
        assertFalse(Files.readString(out).contains("real-private"), "a private project name leaked into --out")
        secrets.filterNot { it.startsWith("/home/operator") }
            .forEach { assertFalse(Files.readString(out).contains(it), "$it leaked into --out") }
    }

    @Test
    fun `the perf tail spans both generations, keeps numbers only and reports an unreadable file`() {
        val env = plant()
        val state = tmp.resolve("state")
        val rotated = (1..150).joinToString("\n") { """{"model":"m-old","outcome":"ok","total":$it}""" }
        Files.writeString(state.resolve("codex-perf.jsonl.1"), rotated + "\n")
        val active = (1..120).joinToString("\n") { """{"model":"m-new","outcome":"ok","total":$it}""" } + "\n" +
            """{"model":"0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b","outcome":["x"],"total":"12","attempts":3,""" +
            """"session":"sess-identifier-1234","prompt":"PLANTEXT the user's secret plan"}""" + "\n" +
            "not json at all\n"
        Files.writeString(state.resolve("codex-perf.jsonl"), active)
        val perf = report(env, withLogs = false).getValue("perf").jsonObject.getValue("codex").jsonObject
        val rows = perf.getValue("rows").jsonArray.map { it.jsonObject }
        assertEquals(200, rows.size, "the last 200 across .1 then the active file")
        assertEquals("m-old", rows.first().getValue("model").jsonPrimitive.content, "the tail starts inside .1")
        assertEquals(79, rows.count { it["model"]?.jsonPrimitive?.content == "m-old" })
        val typed = rows.last()
        assertEquals(setOf("attempts"), typed.keys, "string total, array outcome, foreign keys, UUID model: dropped")
        assertEquals(3, typed.getValue("attempts").jsonPrimitive.content.toInt())
        assertTrue(perf["read_error"] == null)

        Files.delete(state.resolve("codex-perf.jsonl.1"))
        Files.createDirectory(state.resolve("codex-perf.jsonl.1"))
        val broken = report(env, withLogs = false).getValue("perf").jsonObject.getValue("codex").jsonObject
        assertEquals(121, broken.getValue("rows").jsonArray.size, "the readable generation still counts")
        val error = broken.getValue("read_error").jsonPrimitive.content
        assertTrue(error.startsWith("rotated:"), "a fixed generation label, never the head-derived file name: $error")
    }

    @Test
    fun `the log tail spans both generations and reports an unreadable one`() {
        val env = plant()
        val logs = tmp.resolve("logs")
        val older = (1..600).joinToString("\n") { "[2026-09-12 10:00:00] [daemon] perf outcome=ok n=$it x" }
        Files.writeString(logs.resolve("daemon.log.1"), older + "\n")
        val lines = report(env, withLogs = true).getValue("logs").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(500, lines.size, "the last 500 daemon events of both generations, .1 first")
        assertTrue(lines.first().endsWith("perf outcome=ok n=105"), "604 events, pairs kept: " + lines.first())
        assertTrue(lines.last().endsWith("[codex] launch"), lines.last())

        Files.delete(logs.resolve("daemon.log.1"))
        Files.createDirectory(logs.resolve("daemon.log.1"))
        val broken = report(env, withLogs = true)
        assertEquals(4, broken.getValue("logs").jsonArray.size, "the cap holds: the failure is typed metadata")
        val error = broken.getValue("logs_error").jsonPrimitive.content
        assertTrue(error.startsWith("rotated:"), "a fixed generation label: $error")
        assertFalse(error.contains(secrets[5]), "the failure text goes through the redaction: $error")
    }

    @Test
    fun `operator-authored topology values are token-shaped or omitted, unsafe keys become aliases`() {
        val env = plant()
        val evil = TopologyLoader.parse(
            toml.replace("[daemon]", "[daemon]\nmcp_hosting_exclude = [\"PLANTEXT the user's plan\", \"safe-server\"]")
                .replace("discovery_prefix = \"claudex--\"", "discovery_prefix = \"the user's secret plan--\"")
                .replace("[providers.codex]", "[providers.\"ops@example.com\"]")
                .replace("[providers.codex.models]", "[providers.\"ops@example.com\".models]")
                .replace("provider = \"codex\"", "provider = \"ops@example.com\"")
                .replace(
                    "[heads.codex]",
                    "[heads.\"sk-live-abcdefghijklmnop\"]\nclaude = { command = \"PLANTEXT wrapper command\" }",
                ),
        )
        // Checks derived from config quote the prose prefix, the key-shaped head name (as doctor's
        // auth check does: uppercased into an env var name), the wrapper command, the base URL, and
        // an account label — every one an authored value the serialized topology or the pool holds.
        val quoting = DoctorCheck(
            "the user's secret plan--",
            CheckStatus.WARN,
            "prefix 'the user's secret plan--' of head sk-live-abcdefghijklmnop is not a token",
            "rename it in ~/.config/splice/splice.toml",
        )
        val wrapper = DoctorCheck(
            "wrapper",
            CheckStatus.FAIL,
            "'PLANTEXT wrapper command' is not linked; SK-LIVE-ABCDEFGHIJKLMNOP_API_KEY is not set; " +
                "runtime at https://user:hunter2pw@chatgpt.example.invalid/backend-api/codex answered; " +
                "on ops team PLANTEXT (1 of 2 open)",
            null,
        )
        val sections = listOf("configuration" to listOf(quoting, wrapper))
        val pools = mapOf("sk-live-abcdefghijklmnop" to prosePool())
        val out = report(env, withLogs = false, run = DoctorRun(evil, sections, pools))
        assertScrubbed(out)
        val text = out.toString()
        assertEquals(setOf("<head-1>"), out.getValue("perf").jsonObject.keys, "the perf tail follows the alias")
        val topology = out.getValue("topology").jsonObject
        val exclude = topology.getValue("daemon").jsonObject.getValue("mcp_hosting_exclude").jsonArray
        assertEquals(listOf("safe-server"), exclude.map { it.jsonPrimitive.content }, "prose has no shape here")
        assertEquals(setOf("<provider-1>"), topology.getValue("providers").jsonObject.keys)
        val head = topology.getValue("heads").jsonObject.getValue("<head-1>").jsonObject
        assertEquals("<provider-1>", head.getValue("provider").jsonPrimitive.content, "the reference follows the alias")
        assertEquals("null", head.getValue("discovery_prefix").toString(), "an unsafe prefix is omitted, not masked")
        assertEquals("gpt-6-astra", head.getValue("pinned_model").jsonPrimitive.content)
        val check = out.getValue("checks").jsonArray.first().jsonObject
        assertEquals("configuration/<omitted>", check.getValue("id").jsonPrimitive.content, "a prose check name")
        val detail = check.getValue("detail").jsonPrimitive.content
        val expected = "prefix '<omitted>' of head <head-1> is not a token — fix: rename it in " +
            "~/.config/splice/splice.toml"
        assertEquals(expected, detail)
    }

    private fun prosePool() = splice.control.HeadAccountPoolView(
        selectedLabel = "ops team PLANTEXT",
        accounts = listOf(
            splice.control.HeadAccountView("primary", true, false, true, "plus", null, null, null, null),
            splice.control.HeadAccountView("ops team PLANTEXT", false, true, true, "plus", null, null, null, null),
        ),
        lastSwitch = null,
    )

    /** Every authored value a doctor sentence can quote — in any case, transformed or not — is gone. */
    private fun assertScrubbed(out: JsonObject) {
        val text = out.toString()
        listOf("ops@example.com", "sk-live-abcdefghijklmnop", "SK-LIVE", "PLANTEXT", "secret plan", "hunter2pw")
            .forEach { assertFalse(text.contains(it), "$it in the report: $text") }
        val details = out.getValue("checks").jsonArray.map { it.jsonObject.getValue("detail").jsonPrimitive.content }
        assertEquals(
            "'<omitted>' is not linked; <head-1>_API_KEY is not set; runtime at chatgpt.example.invalid answered; " +
                "on <account-2> (1 of 2 open)",
            details[1],
        )
        val accounts = out.getValue("accounts").jsonObject.getValue("<head-1>").jsonObject
        assertEquals("<account-2>", accounts.getValue("selected").jsonPrimitive.content)
    }

    @Test
    fun `an alias never collides with a real name and logs tags follow the same aliases`() {
        val env = plant()
        val two = TopologyLoader.parse(
            toml.replace("[heads.codex]", "[heads.head-1]") +
                "\n[heads.\"sk-live-abcdefghijklmnop\"]\nprovider = \"codex\"\nport = 3997\n" +
                "discovery_prefix = \"x--\"\npinned_model = \"gpt-6-astra\"\n",
        )
        Files.writeString(
            tmp.resolve("logs").resolve("daemon.log"),
            "[2026-09-13 10:00:52] [sk-live-abcdefghijklmnop] perf outcome=ok total=42\n" +
                "[2026-09-13 10:00:53] [head-1][quota] failed PLANTEXT the user secret plan\n" +
                "[2026-09-13 10:00:54] [daemon] turn ERROR PLANTEXT the user said name=Voldemort city=Paris total=7\n",
        )
        val out = report(env, withLogs = true, run = DoctorRun(two, emptyList()))
        val heads = out.getValue("topology").jsonObject.getValue("heads").jsonObject.keys
        assertEquals(setOf("head-1", "<head-2>"), heads, "the real head-1 keeps its name; the unsafe one is aliased")
        val logs = out.getValue("logs").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf(
                "[2026-09-13 10:00:52] [<head-2>] perf outcome=ok total=42",
                "[2026-09-13 10:00:53] [head-1][quota] failed",
                "[2026-09-13 10:00:54] [daemon] turn ERROR",
            ),
            logs,
            "tags aliased, heads kept, prose after an accepted head gone with every pair behind it",
        )
        assertFalse(out.toString().contains("sk-live-abcdefghijklmnop"))
    }

    @Test
    fun `malformed doctor arguments are refused before anything runs or is written`() {
        val env = plant()
        val target = tmp.resolve("never.json")
        val refused = listOf(
            listOf("--jsn"),
            listOf("--json", "--json"),
            listOf("--json", "--out"),
            listOf("--json", "--out", "--with-logs"),
            listOf("--with-logs"),
            listOf("--out", target.toString()),
            listOf("--json", "--out", target.toString(), "extra"),
        )
        for (args in refused) {
            val (ok, printed) = captureStdout { DoctorCommand().doctor(args) { env[it] } }
            assertFalse(ok, "refused: $args")
            assertEquals("", printed, "nothing printed for $args")
        }
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(tmp.resolve("--with-logs")), "a flag never becomes a file name")
        val options = DoctorReportOptions(json = false, withLogs = false, out = null)
        val full = listOf("--live", "--json", "--with-logs", "--out", target.toString())
        assertEquals(DoctorReportOptions(true, true, target, live = true), options.parse(full))
        assertEquals(DoctorReportOptions(false, false, null), options.parse(emptyList()))
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
