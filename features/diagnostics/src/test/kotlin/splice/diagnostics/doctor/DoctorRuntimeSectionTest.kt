// NEW (JW-05): the doctor runtime section. Every other section reads configuration and
// presence; this one reads what HAPPENED — the G20 health counters from /api/heads and the
// per-head perf JSONL outcome tail — so a fully-configured install with dying turns can no
// longer print "Everything checks out."
package splice.diagnostics.doctor

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DoctorRuntimeSectionTest {

    @Test
    fun `provider rate failures and cooldown holds are distinct ended turns`(@TempDir tmp: Path) {
        val head = splice.daemonclient.DaemonProbe.parseHeadsRuntime(
            """{"heads":[{"key":"synthetic","health":{"localOriginErrors":91,"providerErrors":57,
                "provider_rate_limit_turns":4,"cooldown_held_turns":6}}]}""",
        ).single()
        val row = DoctorRuntime().headRuntimeRows(
            head,
            splice.core.config.StatePaths(baseOverride = tmp),
        ).single { it.name == "head synthetic errors" }
        assertEquals(
            "synthetic: 10 turns hit the provider's rate limit after the restart; " +
                "splice held back 6 of them while it cooled down.",
            row.detail,
        )
    }

    @Test
    fun `the rate-limit vendor comes from the declared provider even when the head name suggests another`(
        @TempDir tmp: Path,
    ) {
        val topology = splice.core.topology.Topology(
            providers = mapOf(
                "my-provider" to splice.core.topology.ProviderConfig(
                    splice.core.topology.Dialect.ANTHROPIC_PASSTHROUGH,
                    "http://127.0.0.1:1",
                    splice.core.topology.AuthConfig("client"),
                ),
            ),
            heads = mapOf("claude-openai" to splice.core.topology.HeadConfig("my-provider", 12345, "synthetic--")),
        )
        val answers = DaemonAnswers(
            health = """{"version":"0.4.0","heads":1,"readyHeads":1,"failedHeads":0}""",
            heads = """{"heads":[{"key":"claude-openai","health":{
                "provider_rate_limit_turns":4,"cooldown_held_turns":6}}]}""",
            auth = "{}",
            trace = emptyMap(),
            unmappedTiers = emptyMap(),
        )
        val reads = AnsweredDaemon(answers)
        val env = splice.core.util.EnvReader { if (it == "SPLICE_STATE_DIR") tmp.toString() else null }
        val row = DoctorRuntime().runtimeChecks(DaemonSnapshot(1, reads.health(1)), env, reads, topology)
            .single { it.name == "head claude-openai errors" }
        assertTrue(row.detail.contains("10 turns hit Anthropic's rate limit"), row.detail)
        assertTrue(!row.detail.contains("OpenAI's rate limit"), row.detail)
    }

    @Test
    fun `measured zero rate turns leave a plain error sentence`(@TempDir tmp: Path) {
        val head = splice.daemonclient.DaemonProbe.parseHeadsRuntime(
            """{"heads":[{"key":"synthetic","health":{"localOriginErrors":3,"providerErrors":2,
                "provider_rate_limit_turns":0,"cooldown_held_turns":0}}]}""",
        ).single()
        val row = DoctorRuntime().headRuntimeRows(
            head,
            splice.core.config.StatePaths(baseOverride = tmp),
        ).single { it.name == "head synthetic errors" }
        assertEquals("synthetic: 2 errors at the provider and 3 inside splice since the restart", row.detail)
    }

    @Test
    fun `error sentences omit zero origins and use singular error without claiming failed turns`(@TempDir tmp: Path) {
        val cases = listOf(
            Triple(0L, 2L, "2 errors inside splice"),
            Triple(0L, 1L, "1 error inside splice"),
            Triple(1L, 0L, "1 error at the provider"),
            Triple(13L, 20L, "13 errors at the provider and 20 inside splice"),
        )
        for ((provider, local, words) in cases) {
            val row = DoctorRuntime().headRuntimeRows(
                splice.daemonclient.DaemonProbe.HeadRuntime(
                    key = "synthetic",
                    localOriginErrors = local,
                    providerErrors = provider,
                ),
                splice.core.config.StatePaths(baseOverride = tmp),
            ).first()
            assertEquals("synthetic: $words since the restart", row.detail)
            assertNull(row.details)
        }
    }

    @Test
    fun `one rate-limited turn does not add a sentence about zero cooldown holds`(@TempDir tmp: Path) {
        val row = DoctorRuntime().headRuntimeRows(
            splice.daemonclient.DaemonProbe.HeadRuntime(
                key = "synthetic",
                localOriginErrors = 0,
                providerErrors = 9,
                rateLimit = splice.core.head.RateLimitHealth(1, 0),
            ),
            splice.core.config.StatePaths(baseOverride = tmp),
            "Anthropic",
        ).first()
        assertEquals("synthetic: 1 turn hit Anthropic's rate limit after the restart.", row.detail)
        assertEquals("Provider errors: 9. Errors inside splice: 0.", row.details)
    }

    @Test
    fun `rate headline and raw error counts are separate in the report`(@TempDir tmp: Path) {
        val paths = splice.core.config.StatePaths(baseOverride = tmp)
        val row = DoctorRuntime().headRuntimeRows(
            splice.daemonclient.DaemonProbe.HeadRuntime(
                key = "synthetic",
                localOriginErrors = 91,
                providerErrors = 57,
                rateLimit = splice.core.head.RateLimitHealth(4, 6),
            ),
            paths,
        ).first()
        val writer = splice.diagnostics.doctor.report.DoctorJsonReport(
            envReader = { null },
            claudeVersion = { "synthetic" },
            statePaths = paths,
        )
        val report = writer.build(
            splice.diagnostics.doctor.report.DoctorRun(null, listOf("runtime" to listOf(row))),
            false,
        )
        val check = report.getValue("checks").jsonArray.single().jsonObject
        assertEquals("Provider errors: 57. Errors inside splice: 91.", check["details"]?.jsonPrimitive?.content)
        assertEquals(row.detail, check.getValue("detail").jsonPrimitive.content)
        val sensitive = row.copy(details = "Bearer SYNTHETIC-SECRET-DOCTOR-DETAILS")
        val scrubbed = writer.build(
            splice.diagnostics.doctor.report.DoctorRun(null, listOf("runtime" to listOf(sensitive))),
            false,
        ).getValue("checks").jsonArray.single().jsonObject.getValue("details").jsonPrimitive.content
        assertTrue(scrubbed.contains("<redacted>"), scrubbed)
        assertTrue(!scrubbed.contains("SYNTHETIC-SECRET"), scrubbed)
    }

    private fun runDoctor(env: Map<String, String?>): Pair<Boolean, String> {
        val buf = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buf, true))
        return try {
            DoctorTestPorts.doctor().doctor { name -> env[name] } to buf.toString()
        } finally {
            System.setOut(original)
        }
    }

    // The row-level grade, added when the wall's retirement proof caught the report-level one
    // passing for the wrong reason (2026-09-21). The arm below asserts `splice logs --head codex
    // --tail 50` appears SOMEWHERE in doctor's output — and that exact string is also produced by
    // the perf-tail row in DoctorProbeWrite, so deleting the fix from the error-counter row left
    // the whole report still containing it and the arm still green. A fix belongs to a row: this
    // grades the row that names the errors, which is the one an operator acts on.
    @Test
    fun `the error-counter row carries its own fix, not one borrowed from a sibling row - JW-05`(@TempDir tmp: Path) {
        val statePaths = splice.core.config.StatePaths(baseOverride = tmp.resolve("state"))
        val rows = DoctorRuntime().headRuntimeRows(
            splice.daemonclient.DaemonProbe.HeadRuntime(key = "codex", localOriginErrors = 1, providerErrors = 7),
            statePaths,
        )
        val counters = rows.single { it.name == "head codex errors" }
        assertEquals(CheckStatus.WARN, counters.status, "non-zero errors are a WARN: $counters")
        assertEquals(
            "splice logs --head codex --tail 50",
            counters.fix,
            "the row that names the errors carries the verb that reads them: $counters",
        )
    }

    @Test
    fun `a clean head states it and offers no fix, so the WARN fix is not a constant - JW-05`(@TempDir tmp: Path) {
        val statePaths = splice.core.config.StatePaths(baseOverride = tmp.resolve("state"))
        val rows = DoctorRuntime().headRuntimeRows(
            splice.daemonclient.DaemonProbe.HeadRuntime(key = "codex", localOriginErrors = 0, providerErrors = 0),
            statePaths,
        )
        val counters = rows.single { it.name == "head codex errors" }
        assertEquals(CheckStatus.OK, counters.status, "no errors is OK: $counters")
        assertNull(counters.fix, "a clean row has nothing to fix: $counters")
    }

    private fun baseEnv(tmp: Path, port: Int): Map<String, String?> {
        val state = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(state.resolve("mgmt-key"), "test-key\n")
        return mapOf(
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
            "SPLICE_BIN_DIR" to tmp.resolve("bin").toString(),
            "SPLICE_SHARE_DIR" to tmp.resolve("share").toString(),
            "PATH" to tmp.resolve("bin").toString(),
            "CLAUDEX_STATE_DIR" to state.toString(),
            "SPLICE_CONTROL_PORT" to port.toString(),
        )
    }

    // DR-41a: an EXISTING-but-unreadable mgmt key reported as "missing"/"minted on first launch",
    // sending the operator to re-mint a key sitting there behind a permission error. No daemon is
    // needed: with the daemon stopped the old code said INFO minted-on-first-launch; unreadable
    // must out-rank that guess.
    @Test
    fun `an unreadable mgmt key is reported unreadable, not missing`(@TempDir tmp: Path) {
        val env = baseEnv(tmp, port = 1) // nothing listens on port 1: daemon not running
        val keyFile = tmp.resolve("state").resolve("mgmt-key")
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("-wx------"))
        try {
            val (_, out) = runDoctor(env)
            assertTrue(out.contains("unreadable at"), out)
            assertTrue(!out.contains("minted on first launch"), out)
        } finally {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `an inaccessible mgmt key parent is unreadable, not missing`(@TempDir tmp: Path) {
        val env = baseEnv(tmp, port = 1)
        val stateDir = tmp.resolve("state")
        val original = Files.getPosixFilePermissions(stateDir)
        Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("---------"))
        try {
            val (_, out) = runDoctor(env)
            assertTrue(out.contains("unreadable at"), out)
            assertTrue(!out.contains("minted on first launch"), out)
        } finally {
            Files.setPosixFilePermissions(stateDir, original)
        }
    }

    @Test
    fun `non-zero provider errors and a failing perf tail are WARN rows with fixes - JW-05`(@TempDir tmp: Path) {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val version = splice.core.GATEWAY_VERSION
        server.createContext("/health") { ex ->
            val b = """{"ok":true,"version":"$version","heads":1,"readyHeads":1,"failedHeads":0}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        val headsBody = java.util.concurrent.atomic.AtomicReference(
            """{"heads":[{"key":"codex","health":{"localOriginErrors":1,"providerErrors":7}}]}""",
        )
        server.createContext("/api/heads") { ex ->
            val b = headsBody.get().toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val env = baseEnv(tmp, server.address.port)
            // perf tail: three clean turns, then an upstream failure ~4 minutes ago
            val perf = tmp.resolve("state").resolve("codex-perf.jsonl")
            val now = System.currentTimeMillis()
            val perfLines = listOf(
                """{"ts":${now - 600_000},"outcome":"ok"}""",
                """{"ts":${now - 500_000},"outcome":"ok"}""",
                """{"ts":${now - 400_000},"outcome":"ok"}""",
                """{"ts":${now - 240_000},"outcome":"error:conn-reset"}""",
            )
            Files.writeString(perf, perfLines.joinToString("\n") + "\n")
            val (_, out) = runDoctor(env)
            assertTrue(out.contains("codex: 7 errors at the provider and 1 inside splice since the restart"), out)
            assertTrue(out.contains("splice logs --head codex --tail 50"), out)
            assertTrue(out.contains("1 of last 4 turn(s) failed"), out)
            assertTrue(out.contains("error:conn-reset"), out)
            assertTrue(!out.contains("Everything checks out"), out)
            headsBody.set(
                """{"heads":[{"key":"codex","health":{"localOriginErrors":91,"providerErrors":57,
                    "provider_rate_limit_turns":4,"cooldown_held_turns":6}}]}""",
            )
            val (_, rateOut) = runDoctor(env)
            assertRateFindingOutput(rateOut)
        } finally {
            server.stop(0)
        }
    }

    private fun assertRateFindingOutput(output: String) {
        val finding = output.substringAfter("\n  ! head codex errors\n").substringBefore("\n\n")
        assertEquals(
            listOf(
                "      codex: 10 turns hit the provider's rate limit after the restart; " +
                    "splice held back 6 of them while it cooled down.",
                "      Show the details",
                "        Provider errors: 57. Errors inside splice: 91.",
                "      fix   splice logs --head codex --tail 50",
            ).joinToString("\n"),
            finding,
        )
    }

    // DR-174: the runtime section held its own private mgmt-key reader that collapsed absence and
    // denied access, and then rendered BOTH as "skipped (mgmt-key unreadable)". So a live daemon on
    // a box that has simply never minted a key told the operator the key could not be READ — the
    // mirror of the restart defect, pointing at permissions on a file that is not there. The state
    // dir is deliberately readable here: the ONLY thing wrong is that the key does not exist yet.
    @Test
    fun `a live daemon with no key minted says so, not unreadable - DR-174`(@TempDir tmp: Path) {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val version = splice.core.GATEWAY_VERSION
        server.createContext("/health") { ex ->
            val b = """{"ok":true,"version":"$version","heads":0,"readyHeads":0,"failedHeads":0}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val env = baseEnv(tmp, server.address.port)
            Files.delete(tmp.resolve("state").resolve("mgmt-key"))
            val (_, out) = runDoctor(env)
            assertTrue(out.contains("not minted yet"), "an unminted key must be named as such:\n$out")
            assertTrue(
                !out.contains("mgmt-key unreadable"),
                "a key that was never written is not a key that cannot be read:\n$out",
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `runtime section is INFO-skipped when the daemon is stopped - JW-05`(@TempDir tmp: Path) {
        val freePort = TestPorts.reserve()
        val (_, out) = runDoctor(baseEnv(tmp, freePort))
        assertTrue(out.contains("skipped (daemon stopped)"), out)
    }

    // DR-173 (grok-splice source sweep): a LIVE daemon with zero heads crashed `splice doctor`
    // outright. DoctorRuntime legitimately returns an empty list there — daemon up, key readable,
    // /api/heads answering with an empty array, and DaemonLock.headsRuntime reserves null for a
    // FAILED request — and DoctorCommand.renderSection called maxOf on it, which throws
    // NoSuchElementException. The render loop runs OUTSIDE guarded(), so nothing caught it: an
    // install whose only sin was having no heads yet got a stack trace instead of a report.
    @Test
    fun `a live daemon with zero heads still prints a report - DR-173`(@TempDir tmp: Path) {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val version = splice.core.GATEWAY_VERSION
        server.createContext("/health") { ex ->
            val b = """{"ok":true,"version":"$version","heads":0,"readyHeads":0,"failedHeads":0}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        // The whole fixture: a well-formed EMPTY heads array, which is not an error condition.
        server.createContext("/api/heads") { ex ->
            val b = """{"heads":[]}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val (_, out) = runDoctor(baseEnv(tmp, server.address.port))
            // Reaching an assertion at all is half the arm — before DR-173 runDoctor threw.
            assertTrue(out.contains("runtime"), "the runtime section must still be rendered:\n$out")
            assertTrue(out.contains("nothing to report"), "an empty section must say so:\n$out")
            // ...and the report must still COMPLETE, not stop at the section that was empty.
            assertTrue(
                out.contains("Everything checks out.") || out.contains("issue(s)") || out.contains("No blockers"),
                "the summary line must still be reached:\n$out",
            )
        } finally {
            server.stop(0)
        }
    }
}
