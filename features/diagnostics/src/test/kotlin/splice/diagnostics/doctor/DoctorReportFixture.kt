package splice.diagnostics.doctor

import kotlinx.serialization.json.JsonObject
import splice.core.config.StatePaths
import splice.diagnostics.doctor.report.DoctorJsonReport
import splice.diagnostics.doctor.report.DoctorRun
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** The planted config, perf tail and daemon log the `doctor --json` report tests read, and the report built from them. */
internal class DoctorReportFixture(private val tmp: Path) {

    val toml = """
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

    fun plant(): Map<String, String?> {
        val config = Files.createDirectories(tmp.resolve("config").resolve("splice"))
        Files.writeString(config.resolve("splice.toml"), toml)
        val state = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(state.resolve("mgmt-key"), "mgmt-secret-key-value-abcdef\n")
        Files.writeString(
            state.resolve("codex-perf.jsonl"),
            """
            {"ts":1,"model":"gpt-6-astra","outcome":"ok","compact":false,"cache_cold":"true","account":"ops team PLANTEXT","session":"sess-identifier-1234","prompt":"PLANTEXT the user's secret plan","total":42,"out_tokens":7}
            {"ts":2,"model":"gpt-6-astra","outcome":"client_abort","compact":true,"cache_cold":false,"account":"work","cwd":"/home/operator/projects/private-repo","total":9}
            {"ts":3,"model":"gpt-6-astra","outcome":"PLANTEXT the user's secret plan as an outcome","total":9}
            """.trimIndent() + "\n",
        )
        val logs = Files.createDirectories(tmp.resolve("logs"))
        // The rolled generation an older release wrote: a record that spans lines, whose continuation quotes a line
        // that reads as the escaping writer's own (pre-merge review, Oct 13).
        Files.writeString(
            logs.resolve("daemon.log.1"),
            "[2026-09-12 10:00:00] [codex] turn ERROR upstream body follows\n" +
                "[2026-09-12 10:00:00.000] [codex] up: PLANTEXT the user's secret plan\n" +
                "PLANTEXT the user's secret plan\n",
        )
        Files.writeString(
            logs.resolve("daemon.log"),
            "[2026-09-13 10:00:52.000] [daemon] up: control :3999, heads [codex]\n" +
                "[2026-09-13 10:00:53.000] [codex] turn ERROR auth-missing: refresh with " +
                "Bearer BEARER-SECRET-VALUE-0001 for ops@example.com key sk-live-abcdefghijklmnop\n" +
                "[2026-09-13 10:00:53.000] [codex] turn ERROR upstream compact=false latency=12ms " +
                "type=invalid_request status=400 msg=PLANTEXT the user's secret plan, echoed back by the upstream\n" +
                "[2026-09-13 10:00:54.000] [codex] client gone (session 1) cwd /home/operator/projects/private-repo\n" +
                "[2026-09-13 10:00:55.000] [codex] launch claudex -> cwd " +
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

    fun report(env: Map<String, String?>, withLogs: Boolean, run: DoctorRun? = null): JsonObject {
        val topology = TopologyLoader.parse(toml)
        val given = run
            ?: DoctorRun(topology, listOf("configuration" to listOf(DoctorCheck("topology", CheckStatus.OK, "ok"))))
        return DoctorJsonReport(
            envReader = { env[it] },
            claudeVersion = { "2.1.257 (Claude Code) for ops@example.com" },
            home = java.nio.file.Paths.get("/home/operator"),
            statePaths = StatePaths(envReader = { env[it] }),
        ).build(given, withLogs)
    }
}
