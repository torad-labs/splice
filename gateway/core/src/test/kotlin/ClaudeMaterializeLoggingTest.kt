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
import splice.core.util.Cancellables
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

// DR-104: replaceWithSymlink's bare `finally Files.deleteIfExists(staged)` let a cleanup throw
// REPLACE the in-flight move failure — the decline log then named the wrong cause (the staged
// path instead of the failed staged -> dst move). The two-path " -> " form is the discriminator:
// only the move failure carries both paths.
class ClaudeMaterializeCleanupTest {

    private val optionsCache = kotlinx.serialization.json.buildJsonObject { put("cache", "x") }

    @Test
    fun `the decline log names the move failure, not the staged cleanup's - DR-104`(@TempDir home: Path) {
        val global = home.resolve(".claude")
        Files.createDirectories(global.resolve("agents"))
        val configDir = home.resolve(".claude-head")
        val log = mutableListOf<String>()
        val breaking = SymlinkOp { link, target ->
            val made = Files.createSymbolicLink(link, target)
            // Freeze the parent AFTER staging: the move fails (AccessDenied staged -> dst) AND the
            // finally's deleteIfExists fails (AccessDenied staged) — the log must name the former.
            Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("r-x------"))
            made
        }
        try {
            Cancellables.discard(
                runCatching {
                    ClaudeConfigMaterializer(home, log = LogSink { log += it }, symlink = breaking)
                        .materialize(
                            MaterializeSpec(
                                configDir = configDir,
                                policy = ClaudePolicy(share = setOf("agents"), isolate = emptySet()),
                                availableModelIds = listOf("m1"),
                                defaultModel = "m1",
                                modelOptionsCache = optionsCache,
                                statuslineCommand = "curl",
                            ),
                        )
                },
                "this arm asserts on the LOGGED cause, not the outcome — either result is in scope",
            )
        } finally {
            Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("rwx------"))
        }
        val decline = log.filter { it.contains("'agents' NOT linked") }
        assertTrue(decline.isNotEmpty(), "the failed link must be logged; got $log")
        assertTrue(
            decline.any { it.contains(" -> ") },
            "the logged cause must be the MOVE failure (two-path form), not the staged cleanup's; got $decline",
        )
    }

    // DR-105: readSettingsModelBase throws on a present-but-unreadable head settings.json, but ran
    // from writeSettings AFTER linkShared and the hook writes — contradicting the header's
    // "validate every aborting source BEFORE any mutation" and leaving the half-built config dir
    // the comment says cannot happen. The abort must land with nothing yet touched.
    @Test
    fun `an unreadable settings json aborts BEFORE any mutation - DR-105`(@TempDir home: Path) {
        val global = home.resolve(".claude")
        Files.createDirectories(global.resolve("agents"))
        val configDir = Files.createDirectories(home.resolve(".claude-head"))
        val settings = configDir.resolve("settings.json")
        Files.writeString(settings, """{"model":"m1"}""")
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("---------"))
        val log = mutableListOf<String>()
        try {
            val outcome = runCatching {
                ClaudeConfigMaterializer(home, log = LogSink { log += it })
                    .materialize(
                        MaterializeSpec(
                            configDir = configDir,
                            policy = ClaudePolicy(share = setOf("agents"), isolate = emptySet()),
                            availableModelIds = listOf("m1"),
                            defaultModel = "m1",
                            modelOptionsCache = optionsCache,
                            statuslineCommand = "curl",
                            loginCommand = "claudex login",
                            signInLabel = "Codex",
                        ),
                    )
            }
            assertTrue(outcome.isFailure, "an unreadable real settings.json must abort the materialize")
            assertTrue(
                !Files.exists(configDir.resolve("agents"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "the abort must land BEFORE linkShared mutates — no half-built config dir",
            )
            assertTrue(
                Files.list(configDir).use { entries -> entries.allMatch { it == settings } },
                "nothing but the pre-existing settings.json may exist after the abort",
            )
        } finally {
            Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-------"))
        }
    }
}
