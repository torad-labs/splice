// NEW: V4-260 — the files a head keeps go when their promise ends (Marlin's disk-writes audit at
// 99244e63a). A REAL daemon starts over a state dir seeded the way earlier runs left it: an expired
// compaction answer beside a fresh one, code-mode state for a head whose provider has code mode off
// and for a head the topology no longer names, and trace days for a head with trace off, one with
// trace on, one removed and one whose trace value is not a bool (V4-286). Heads are assembled only
// at the daemon's start, so that start is every head's start and the moment a removed head is first
// known to be gone. One test per store.
package splice.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.RefreshAttempt
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.head.MockChatGptUpstream
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.LocalDate
import java.time.ZoneOffset

/** Past the two hours a compaction answer is kept for its retry (RECORDING_TTL_MS). */
private const val THREE_HOURS_MS = 3 * 60 * 60 * 1000L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeptFilesLifetimeTest {

    private val mock = MockChatGptUpstream()
    private val controlPort = TestPorts.reserve()
    private val codexPort = TestPorts.reserve()
    private val plainPort = TestPorts.reserve()
    private val typoPort = TestPorts.reserve()
    private val today = LocalDate.now(ZoneOffset.UTC).toString()
    private lateinit var paths: StatePaths
    private lateinit var daemon: Daemon

    // claudex: code mode on (the chatgpt-oauth default), trace off. plain: code mode off, trace on.
    // typo (V4-286): a trace value that is not a bool, which runs the head untraced and keeps its days.
    private fun topologyToml(authFile: String) = """
        [daemon]
        control_port = $controlPort

        [providers.codex]
        dialect = "openai-responses"
        base_url = "${mock.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "$authFile" }

        [[providers.codex.models]]
        id = "gpt-5.6-sol"
        label = "Sol"
        context_window = 272000

        [providers.plain]
        dialect = "openai-responses"
        base_url = "${mock.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "$authFile" }
        quirks = { code_mode = false }

        [[providers.plain.models]]
        id = "gpt-5.6-sol"
        label = "Sol"
        context_window = 272000

        [heads.claudex]
        provider = "codex"
        port = $codexPort
        discovery_prefix = "claude-codex--"
        pinned_model = "gpt-5.6-sol"

        [heads.plain]
        provider = "plain"
        port = $plainPort
        discovery_prefix = "claude-plain--"
        pinned_model = "gpt-5.6-sol"

        [heads.plain.overrides]
        trace = "true"

        [heads.typo]
        provider = "plain"
        port = $typoPort
        discovery_prefix = "claude-typo--"
        pinned_model = "gpt-5.6-sol"

        [heads.typo.overrides]
        trace = "enabled"
    """.trimIndent()

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("v4260-kept-files")
        val authFile = tmp.resolve("auth.json")
        Files.writeString(authFile, """{"tokens":{"access_token":"tok-1","account_id":"acct-1","refresh_token":"r"}}""")
        paths = StatePaths(baseOverride = tmp.resolve("state"))
        seed(paths.compactionRecordingsDir("claudex").resolve("expired.json"), ageMs = THREE_HOURS_MS)
        seed(paths.compactionRecordingsDir("claudex").resolve("fresh.json"))
        seed(paths.compactionRecordingsDir("removed").resolve("answer.json"))
        listOf("claudex", "plain", "removed").forEach { head ->
            seed(paths.stateDir.resolve("$head-code-mode.json"))
            seed(paths.traceDir.resolve("$head-$today.jsonl"))
        }
        seed(paths.traceDir.resolve("removed-$today.jsonl.lock"))
        seed(paths.traceDir.resolve("typo-$today.jsonl"))
        daemon = Daemon(
            topology = TopologyLoader.parse(topologyToml(authFile.toString().replace("\\", "/"))),
            statePaths = paths,
            dashboardHtml = { "<!doctype html><title>splice</title>" },
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { daemon.start() }
        awaitListening(controlPort, codexPort, plainPort, typoPort)
    }

    @AfterAll
    fun tearDown() {
        runBlocking { daemon.stop() }
        mock.stop()
    }

    @Test
    fun `an expired compaction answer is gone at its head's start, and a removed head's answers with it`() {
        val claudex = paths.compactionRecordingsDir("claudex")
        assertFalse(Files.exists(claudex.resolve("expired.json")), "an answer past its 2 h waited for the next save")
        assertTrue(Files.exists(claudex.resolve("fresh.json")), "an answer within its 2 h is kept for its retry")
        assertFalse(Files.exists(paths.compactionRecordingsDir("removed")), "a removed head's answers are kept")
    }

    @Test
    fun `code-mode state is gone for a head with code mode off and a removed head, and kept where it is on`() {
        assertTrue(Files.exists(paths.stateDir.resolve("claudex-code-mode.json")), "code mode is on for claudex")
        assertFalse(Files.exists(paths.stateDir.resolve("plain-code-mode.json")), "code mode is off for plain")
        assertFalse(Files.exists(paths.stateDir.resolve("removed-code-mode.json")), "that head is removed")
    }

    @Test
    fun `trace days are gone for a head with trace off and a removed head, and kept where trace is on`() {
        assertFalse(Files.exists(paths.traceDir.resolve("claudex-$today.jsonl")), "trace is off for claudex")
        assertTrue(Files.exists(paths.traceDir.resolve("plain-$today.jsonl")), "trace is on for plain")
        assertFalse(Files.exists(paths.traceDir.resolve("removed-$today.jsonl")), "that head is removed")
        assertFalse(Files.exists(paths.traceDir.resolve("removed-$today.jsonl.lock")), "a day's lock goes with it")
    }

    @Test
    fun `a trace value that is not a bool keeps the head's trace days - V4-286`() {
        assertTrue(Files.exists(paths.traceDir.resolve("typo-$today.jsonl")), "trace = \"enabled\" was read as off")
    }

    private fun seed(file: Path, ageMs: Long = 0) {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{}\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - ageMs))
    }
}
