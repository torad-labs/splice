// NEW: v0.4.0 FEATURES.md §6 — the doctor report's boundaries under hostile input: a path-shaped
// head key never reaches the file system, a dangling generation is said under a fixed label, JVM
// properties are bounded tokens, pool-only heads get distinct aliases, and credential_present rides
// per account (V4-10 REVIEW 4 R1).
package splice.app.cli.doctor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.SafeNames
import splice.control.HeadAccountPoolView
import splice.control.HeadAccountView
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
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
        val report = DoctorReport(envReader = { env[it] }, claudeVersion = { "2.1.257" }, home = tmp)
        return report.build(run, withLogs = false)
    }

    private fun account(label: String, present: Boolean) = HeadAccountView(
        label = label,
        primary = label == "primary",
        selected = false,
        available = present,
        plan = null,
        fiveHourUsedPercent = null,
        fiveHourResetEpochSeconds = null,
        sevenDayUsedPercent = null,
        sevenDayResetEpochSeconds = null,
        credentialPresent = present,
    )

    @Test
    fun `credential_present rides per account`() {
        val pool = HeadAccountPoolView("work", listOf(account("primary", false), account("work", true)), null)
        val out = build(DoctorRun(topology("codex"), emptyList(), mapOf("codex" to pool)))
        val accounts = out.getValue("accounts").jsonObject.getValue("codex").jsonObject.getValue("accounts").jsonArray
        val present = accounts.map { it.jsonObject.getValue("credential_present").jsonPrimitive.content }
        assertEquals(listOf("false", "true"), present, "a dangling primary is visible in the report")
    }

    @Test
    fun `an unknown selection is reported as unknown, never as the primary - V4-10 S3`() {
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
        assertEquals(CheckStatus.WARN, rotatedFailure.status, rotatedFailure.detail)
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
}
