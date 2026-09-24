// NEW (restructure PR 6 §2.7, home of the jw_02 wall): the degraded-boot rows doctor was
// structurally blind to. /health has carried heads/readyHeads/failedHeads since the shim's
// converge-wait, and doctor extracted only `version` — so a daemon whose every head failed to
// start was blessed with "Everything checks out."
//
// DoctorCommandTest already drives the 2-of-3-failed case through a live report. This file grades
// the collaborator directly, because three of its verdicts are invisible at report level: a
// counter-less foreign listener must produce NO row rather than a fabricated one, a stopped daemon
// must produce no per-head rows at all, and the per-head TCP probe must distinguish a
// bound-but-unassembled head from an unbound one. The probe is what the wall called "not
// listening", and a WARN there is the difference between "the daemon is up" and "the daemon is up
// and this head is not on its port".
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.testing.TestPorts
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import java.net.ServerSocket

class DoctorHeadChecksTest {

    private val checks = DoctorHeadChecks(DoctorRuntime())

    private fun health(heads: Int?, ready: Int?, failed: Int?) =
        HealthView(version = "0.4.0", heads = heads, readyHeads = ready, failedHeads = failed)

    private fun topology(vararg headPorts: Pair<String, Int>): Topology = Topology(
        providers = mapOf(
            "codex" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                auth = AuthConfig(kind = "chatgpt-oauth"),
            ),
        ),
        heads = headPorts.toMap().mapValues { (key, port) ->
            HeadConfig(
                provider = "codex",
                port = port,
                discoveryPrefix = "claude-codex--",
                pinnedModel = "gpt-5.6-sol",
                claude = ClaudeWrapperConfig(command = key),
            )
        },
    )

    private fun rowFor(key: String, rows: List<DoctorCheck>) =
        rows.single { it.name == "head $key" }

    @Test
    fun `a head whose port nobody holds is a WARN naming the port - JW-02`() {
        // A bound-but-unassembled head and an unbound one are the same to the counters; only the
        // probe tells them apart, and this is the case the operator is chasing when a wrapper
        // command hangs.
        val free = TestPorts.reserve()
        val rows = checks.headChecks(DaemonSnapshot(port = 1, health = health(1, 1, 0)), topology("codex" to free))
        val row = rowFor("codex", rows)
        assertEquals(CheckStatus.WARN, row.status, "an unbound head port is a WARN: $row")
        assertTrue(row.detail.contains(":$free"), "the row names the port: ${row.detail}")
        assertTrue(row.detail.contains("not listening"), "the row says what is wrong: ${row.detail}")
    }

    @Test
    fun `a head whose port is held reads listening, so the probe is not a constant - JW-02`() {
        ServerSocket(0).use { held ->
            val rows = checks.headChecks(
                DaemonSnapshot(port = 1, health = health(1, 1, 0)),
                topology("codex" to held.localPort),
            )
            val row = rowFor("codex", rows)
            assertEquals(CheckStatus.INFO, row.status, "a bound head port is INFO: $row")
            assertTrue(row.detail.contains("listening"), "${row.detail}")
            assertTrue(!row.detail.contains("not listening"), "a held port must not read as unbound: ${row.detail}")
        }
    }

    @Test
    fun `failed heads are a FAIL counting both sides - JW-02`() {
        val rows = checks.headChecks(DaemonSnapshot(port = 1, health = health(3, 1, 2)), null)
        val summary = rows.single { it.detail.contains("FAILED to start") }
        assertEquals(CheckStatus.FAIL, summary.status, "dead heads are a failure, not a note: $summary")
        assertTrue(summary.detail.contains("2 of 3"), "the row counts both sides: ${summary.detail}")
    }

    @Test
    fun `a listener without the counters produces no row rather than a fabricated one - JW-02`() {
        // A foreign or ancient listener answers /health without heads/readyHeads/failedHeads. A
        // real daemon always sends all three, so absence is "nothing honest to report" — never a
        // zero, which would read as a healthy daemon with no heads.
        val rows = checks.headChecks(DaemonSnapshot(port = 1, health = health(null, null, null)), null)
        assertEquals(emptyList<DoctorCheck>(), rows, "no counters means no verdict: $rows")
    }

    @Test
    fun `a stopped daemon is probed for nothing - JW-02`() {
        // A stopped daemon's closed ports are expected, not findings: probing them would turn
        // every `splice doctor` on a stopped install into a wall of WARNs.
        val free = TestPorts.reserve()
        val rows = checks.headChecks(DaemonSnapshot(port = 1, health = null), topology("codex" to free))
        assertEquals(emptyList<DoctorCheck>(), rows, "a stopped daemon yields no head rows: $rows")
    }
}
