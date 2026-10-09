// NEW: v0.4.0 FEATURES.md §6 — the doctor report's boundaries under hostile input: a path-shaped
// head key never reaches the file system, a dangling generation is said under a fixed label, JVM
// properties are bounded tokens, pool-only heads get distinct aliases, and credential_present rides
// per account (V4-10 REVIEW 4 R1).
package splice.diagnostics.doctor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.pool.HeadAccountCredential
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.config.StatePaths
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.DaemonConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.diagnostics.doctor.report.DoctorJsonReport
import splice.diagnostics.doctor.report.DoctorRedaction
import splice.diagnostics.doctor.report.DoctorReportFiles
import splice.diagnostics.doctor.report.DoctorRun
import splice.diagnostics.doctor.report.SafeNames
import java.nio.file.Files
import java.nio.file.Path

class DoctorReportHardeningTest {

    @TempDir
    lateinit var tmp: Path

    private fun topology(vararg headKeys: String): Topology = Topology(
        providers = mapOf(
            "codex" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                auth = AuthConfig(kind = "chatgpt-oauth"),
            ),
        ),
        heads = headKeys.associateWith { key ->
            HeadConfig(
                provider = "codex",
                port = 3099,
                discoveryPrefix = "claude-codex--",
                pinnedModel = "gpt-5.6-sol",
                claude = ClaudeWrapperConfig(command = key),
            )
        },
    )

    private fun build(run: DoctorRun): JsonObject {
        val state = Files.createDirectories(tmp.resolve("state"))
        val env = mapOf("CLAUDEX_STATE_DIR" to state.toString(), "XDG_CONFIG_HOME" to tmp.resolve("config").toString())
        val report = DoctorJsonReport(
            envReader = { env[it] },
            claudeVersion = { "2.1.257" },
            home = tmp,
            statePaths = StatePaths(envReader = { env[it] }),
        )
        return report.build(run, withLogs = false)
    }

    private fun account(label: String, present: Boolean) = HeadAccountView(
        label = label,
        primary = label == "primary",
        selected = false,
        available = present,
        plan = null,
        credential = HeadAccountCredential(present = present),
    )

    @Test
    fun `a bare key in a config string never appears in the report`() {
        val key = "AbCdEfGhIjKlMnOpQrStUvWxYz012345"
        val authored = topology("codex").copy(daemon = DaemonConfig(showReasoning = key))
        val report = build(DoctorRun(authored, emptyList())).toString()
        assertFalse(report.contains(key), report)
    }

    @Test
    fun `credential_present rides per account`() {
        val pool = HeadAccountPoolView("work", listOf(account("primary", false), account("work", true)), null)
        val out = build(DoctorRun(topology("codex"), emptyList(), mapOf("codex" to pool)))
        val accounts = out.getValue("accounts").jsonObject.getValue("codex").jsonObject.getValue("accounts").jsonArray
        val present = accounts.map { it.jsonObject.getValue("credential_present").jsonPrimitive.content }
        assertEquals(listOf("false", "true"), present, "a dangling primary is visible in the report")
    }

    @Test
    fun `an unknown selection is reported as unknown, never as the primary`() {
        val accounts = listOf(account("primary", true), account("work", true))
        val pool = HeadAccountPoolView("work", accounts, null, selectionUnknown = true)
        val out = build(DoctorRun(topology("codex"), emptyList(), mapOf("codex" to pool)))
        val codex = out.getValue("accounts").jsonObject.getValue("codex").jsonObject
        assertEquals("null", codex.getValue("selected").toString(), "the text check says selection unknown")
        assertEquals("true", codex.getValue("selection_unknown").jsonPrimitive.content)
        val known = HeadAccountPoolView("work", accounts, null)
        val knownOut = build(DoctorRun(topology("codex"), emptyList(), mapOf("codex" to known)))
        val knownCodex = knownOut.getValue("accounts").jsonObject.getValue("codex").jsonObject
        assertEquals("work", knownCodex.getValue("selected").jsonPrimitive.content, "a safe label passes as is")
    }

    @Test
    fun `a path-shaped head key never reaches the file system and prints under its alias`() {
        val evil = "../../etc/passwd"
        val out = build(DoctorRun(topology(evil), emptyList()))
        val perf = out.getValue("perf").jsonObject
        assertEquals(setOf("<head-1>"), perf.keys)
        val entry = perf.getValue("<head-1>").jsonObject
        assertEquals(0, entry.getValue("rows").jsonArray.size)
        assertTrue(entry.getValue("read_error").jsonPrimitive.content.contains("not a safe file name"))
        assertFalse(out.toString().contains("passwd"), out.toString())
    }

    @Test
    fun `the report excludes legacy probes before selecting recent work`() {
        val state = Files.createDirectories(tmp.resolve("state"))
        val clean = """{"ts":1,"model":"synthetic-model","outcome":"ok","total":3}"""
        val probe = """{"ts":2,"model":"","outcome":"error:upstream-failed","req_bytes":30}"""
        Files.writeString(
            state.resolve("codex-perf.jsonl"),
            (listOf(clean) + List(500) { probe }).joinToString("\n") + "\n",
        )
        val rows = build(DoctorRun(topology("codex"), emptyList()))
            .getValue("perf").jsonObject.getValue("codex").jsonObject.getValue("rows").jsonArray
        assertEquals(1, rows.size)
        assertEquals("ok", rows.single().jsonObject.getValue("outcome").jsonPrimitive.content)
    }

    @Test
    fun `a dangling rotated generation is reported under a fixed label, the active rows still count`() {
        val state = Files.createDirectories(tmp.resolve("state"))
        Files.createSymbolicLink(state.resolve("codex-perf.jsonl.1"), state.resolve("vanished"))
        Files.writeString(state.resolve("codex-perf.jsonl"), """{"ts":1,"outcome":"ok","total":3}""" + "\n")
        val perf = build(DoctorRun(topology("codex"), emptyList())).getValue("perf").jsonObject
        val entry = perf.getValue("codex").jsonObject
        assertEquals(1, entry.getValue("rows").jsonArray.size)
        assertEquals("rotated: dangling symlink", entry.getValue("read_error").jsonPrimitive.content)
    }

    @Test
    fun `JVM properties are bounded tokens or omitted`() {
        val before = System.getProperty("os.version")
        try {
            System.setProperty("os.version", "6.1 built for ops@example.com")
            val out = build(DoctorRun(topology("codex"), emptyList()))
            assertEquals("<omitted>", out.getValue("os").jsonObject.getValue("version").jsonPrimitive.content)
            assertFalse(out.toString().contains("example.com"))
            assertTrue(out.getValue("jvm").jsonObject.getValue("version").jsonPrimitive.content != "<omitted>")
        } finally {
            System.setProperty("os.version", before)
        }
    }

    @Test
    fun `pool-only heads get their own aliases and case-colliding names scrub exactly`() {
        val redaction = DoctorRedaction(tmp)
        val empty = HeadAccountPoolView(null, emptyList(), null)
        val names = SafeNames(redaction, topology("codex"), mapOf("Ops Team A" to empty, "ops team a" to empty))
        assertEquals("codex", names.head("codex"))
        assertEquals("<head-2>", names.head("Ops Team A"))
        assertEquals("<head-3>", names.head("ops team a"))
        assertEquals("<head-2> then <head-3>", names.scrub("Ops Team A then ops team a"))
    }

    @Test
    fun `the turns check reads both generations, bounded, and says when one cannot be read`() {
        val state = Files.createDirectories(tmp.resolve("state"))
        val perf = state.resolve("codex-perf.jsonl")
        val now = System.currentTimeMillis()
        val failed = """{"ts":$now,"outcome":"error:upstream-failed"}""" + "\n"
        Files.writeString(state.resolve("codex-perf.jsonl.1"), failed)
        Files.writeString(perf, """{"ts":$now,"outcome":"ok"}""" + "\n")
        val probe = DoctorProbeWrite(files = DoctorReportFiles(DoctorRedaction(tmp)))
        val rotatedFailure = probe.perfTailRow("codex", perf)
        // the rotated failure was read (counted), and the turn after it was clean: history, not a fault
        assertEquals(CheckStatus.INFO, rotatedFailure.status, rotatedFailure.detail)
        assertTrue(rotatedFailure.detail.startsWith("1 of last 2 turn(s) failed"), rotatedFailure.detail)

        Files.delete(state.resolve("codex-perf.jsonl.1"))
        Files.createDirectory(state.resolve("codex-perf.jsonl.1"))
        val partial = probe.perfTailRow("codex", perf)
        assertEquals(CheckStatus.OK, partial.status, partial.detail)
        assertTrue(partial.detail.contains("a perf file could not be read: rotated:"), partial.detail)

        Files.delete(perf)
        Files.createDirectory(perf)
        val unread = probe.perfTailRow("codex", perf)
        assertEquals(CheckStatus.WARN, unread.status)
        assertTrue(unread.detail.startsWith("perf file could not be read: rotated:"), unread.detail)
    }

    // Console review 2026-09-24: the live doctor printed "last failure: 28710m ago". The age of the
    // last failure reads in the largest whole unit, the wording `splice status` uses for a switch.
    @Test
    fun `the last failure's age reads in the largest whole unit`() {
        val state = Files.createDirectories(tmp.resolve("state"))
        val perf = state.resolve("codex-perf.jsonl")
        val probe = DoctorProbeWrite(files = DoctorReportFiles(DoctorRedaction(tmp)))
        val cases = mapOf(
            20L * 86_400_000 + 60_000 to "20d ago",
            5L * 3_600_000 + 60_000 to "5h ago",
            3L * 60_000 + 5_000 to "3m ago",
        )
        cases.forEach { (age, words) ->
            val ts = System.currentTimeMillis() - age
            Files.writeString(perf, """{"ts":$ts,"outcome":"error:upstream-failed"}""" + "\n")
            val row = probe.perfTailRow("codex", perf)
            assertTrue(row.detail.contains("last failure: $words"), "$words: ${row.detail}")
        }
    }

    // Console review 2026-09-29: Needs you listed nine heads whose last failure was 3 to 35 days old
    // ("last failure: 35d ago"). A head's failed turns warn while the newest failure is under a day
    // old; after that the same sentence is history, read at INFO.
    @Test
    fun `a head's last failure warns for a day, then reads as history`() {
        val state = Files.createDirectories(tmp.resolve("state"))
        val perf = state.resolve("codex-perf.jsonl")
        val probe = DoctorProbeWrite(files = DoctorReportFiles(DoctorRedaction(tmp)))
        val cases = mapOf(
            23L * 3_600_000 to CheckStatus.WARN,
            25L * 3_600_000 to CheckStatus.INFO,
            35L * 86_400_000 + 60_000 to CheckStatus.INFO,
        )
        cases.forEach { (age, status) ->
            val ts = System.currentTimeMillis() - age
            Files.writeString(perf, """{"ts":$ts,"outcome":"error:auth-missing"}""" + "\n")
            val row = probe.perfTailRow("codex", perf)
            assertEquals(status, row.status, "${age}ms: ${row.detail}")
            assertTrue(row.detail.startsWith("1 of last 1 turn(s) failed; last failure:"), row.detail)
        }
        Files.writeString(perf, """{"outcome":"error:auth-missing"}""" + "\n")
        val undated = probe.perfTailRow("codex", perf)
        assertEquals(CheckStatus.WARN, undated.status, "a failure with no time is never called old: ${undated.detail}")
    }

    @Test
    fun `legacy liveness probes neither fail a command nor fill its recent work window`() {
        val perf = Files.createDirectories(tmp.resolve("probe-state")).resolve("synthetic-perf.jsonl")
        val reader = DoctorProbeWrite(files = DoctorReportFiles(DoctorRedaction(tmp)))
        val now = System.currentTimeMillis()
        val probe = """{"ts":$now,"model":"","outcome":"error:upstream-failed","compact":false,"req_bytes":30}"""
        val clean = """{"ts":$now,"model":"synthetic-model","outcome":"ok","compact":false}"""
        Files.writeString(perf, (listOf(clean) + List(60) { probe }).joinToString("\n") + "\n")
        val healthy = reader.perfTailRow("synthetic", perf)
        assertEquals(CheckStatus.OK, healthy.status, healthy.detail)
        assertEquals("last 1 turn(s) clean", healthy.detail)
        Files.writeString(perf, List(20) { probe }.joinToString("\n") + "\n")
        assertEquals(CheckStatus.INFO, reader.perfTailRow("synthetic", perf).status)
        val failure = """{"ts":$now,"model":"synthetic-model","outcome":"error:upstream-failed"}"""
        Files.writeString(perf, (listOf(failure) + List(60) { probe }).joinToString("\n") + "\n")
        val failed = reader.perfTailRow("synthetic", perf)
        assertEquals(CheckStatus.WARN, failed.status, "real failure must remain: ${failed.detail}")
        assertTrue(failed.detail.startsWith("1 of last 1 turn(s) failed"), failed.detail)
    }

    // V4-444, console review 2026-09-29: claude-splice failed 18 of its last 20 turns in a burst, then
    // answered twice, and doctor still warned (and Needs you listed it) for a day. A head is failing when
    // its newest turn failed, or 3 of its newest 5 did and the newest two are not both clean.
    @Test
    fun `a burst of failures followed by two good turns has recovered, and one failing now still warns`() {
        val state = Files.createDirectories(tmp.resolve("state"))
        val perf = state.resolve("codex-perf.jsonl")
        val probe = DoctorProbeWrite(files = DoctorReportFiles(DoctorRedaction(tmp)))
        val now = System.currentTimeMillis()

        // oldest first: F is a failed turn, . a clean one
        fun read(turns: String): DoctorCheck {
            val lines = turns.mapIndexed { i, turn ->
                val outcome = if (turn == 'F') "error:upstream-failed" else "ok"
                """{"ts":${now - (turns.length - i) * 60_000},"outcome":"$outcome"}"""
            }
            Files.writeString(perf, lines.joinToString("\n") + "\n")
            return probe.perfTailRow("codex", perf)
        }
        val burst = read("FFFFFF..")
        assertEquals(CheckStatus.INFO, burst.status, burst.detail)
        assertTrue(burst.detail.startsWith("6 of last 8 turn(s) failed; last failure: "), burst.detail)
        assertEquals(CheckStatus.WARN, read("..F").status, "the newest turn failed")
        assertEquals(CheckStatus.WARN, read("F.F.F").status, "a head failing every other turn is still failing")
        assertEquals(CheckStatus.WARN, read("FFFF.").status, "one good turn after four failures is not a recovery")
        assertEquals(CheckStatus.INFO, read(".F.F.").status, "two failures in five, ended clean")
    }
}
