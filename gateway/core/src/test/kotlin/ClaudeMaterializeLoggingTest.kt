// DR-39 redo (codex): the materializer's three failure-logging legs existed but nothing PINNED
// them — removing the ordinary link-failure log or the sessions catch survived every test. Each
// leg gets a deterministic injection: a SymlinkOp that fails (the seam DR-11 opened for exactly
// this), and a sessions dir whose listing throws mid-link. Split from ClaudeConfigMaterializerTest
// (detekt LargeClass headroom).
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.launch.MaterializeSpec
import splice.core.launch.SymlinkOp
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText

class ClaudeMaterializeLoggingTest {

    private val optionsCache = buildJsonObject { put("cache", "codex-models") }

    private fun spec(configDir: Path, policy: ClaudePolicy) = MaterializeSpec(
        configDir = configDir,
        policy = policy,
        availableModelIds = listOf("m1"),
        defaultModel = "m1",
        modelOptionsCache = optionsCache,
        statuslineCommand = "\"/usr/bin/curl\" -s :3096/statusline",
    )

    private fun seedGlobal(home: Path) {
        val global = home.resolve(".claude")
        Files.createDirectories(global.resolve("agents"))
        global.resolve("CLAUDE.md").writeText("global rules")
        home.resolve(".claude.json").writeText("""{"mcpServers":{}}""")
    }

    // The ordinary failure leg: a symlink create that fails (ENOSPC, an LSM denial) must name the
    // item and the consequence. Removing linkOneShared's failure log survives every other test.
    @Test
    fun `a failed shared link names the item and its consequence - DR-39`(@TempDir home: Path) {
        seedGlobal(home)
        val log = mutableListOf<String>()
        val policy = ClaudePolicy(share = setOf("agents", "CLAUDE.md"), isolate = emptySet())

        ClaudeConfigMaterializer(
            home,
            log = LogSink { log += it },
            symlink = SymlinkOp { _, _ -> throw IOException("injected ENOSPC") },
        ).materialize(spec(home.resolve(".claude-head"), policy))

        assertTrue(
            log.any { it.contains("'agents' NOT linked") && it.contains("without the operator's agents") },
            "the failed agents link must name itself and its consequence, got $log",
        )
    }

    // The sessions leg: link() THROWS when the head's real sessions dir cannot even be listed
    // (write+search but no read) — that throw must be caught and named, not fail the materialize
    // and not vanish. Removing the catch's log survives every other test.
    @Test
    fun `a sessions migration throw is caught and named - DR-39`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        val sessions = Files.createDirectories(configDir.resolve("sessions"))
        Files.writeString(sessions.resolve("s1.jsonl"), "{}")
        Files.setPosixFilePermissions(sessions, PosixFilePermissions.fromString("-wx------"))
        val log = mutableListOf<String>()
        val policy = ClaudePolicy(share = setOf("sessions"), isolate = emptySet())

        try {
            ClaudeConfigMaterializer(home, log = LogSink { log += it })
                .materialize(spec(configDir, policy))
        } finally {
            Files.setPosixFilePermissions(sessions, PosixFilePermissions.fromString("rwx------"))
        }

        assertTrue(
            log.any { it.contains("sessions registry NOT linked") },
            "a thrown sessions link must be caught and named, got $log",
        )
    }
}
