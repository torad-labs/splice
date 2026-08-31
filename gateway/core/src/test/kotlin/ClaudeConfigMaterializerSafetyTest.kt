// DR-11 redo: the failure/atomicity arms for ClaudeConfigMaterializer, split from the behaviour
// suite so neither class trips detekt's LargeClass (the materializer is high-blast-radius and its
// test surface is large). These pin the three codex gaps: (1) the strict local read runs before any
// mutation, so a corrupt local aborts touching nothing; (2) no settings-symlink pre-delete window;
// (3) the shared-link swap never destroys the operator's pre-existing file when a symlink create
// fails. Fixtures are duplicated deliberately — a shared object would need a member-extension the
// no-top-level-function law makes awkward to import, and the copies are a handful of lines.
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ClaudeConfigMaterializerSafetyTest {

    private val allPolicy = ClaudePolicy(
        share = setOf("settings", "mcps", "agents", "commands", "skills", "hooks", "plugins", "CLAUDE.md"),
        isolate = emptySet(),
    )
    private val optionsCache: JsonElement = buildJsonObject { put("cache", "codex-models") }
    private val statusline = "\"/usr/bin/curl\" -s :3096/statusline"

    private fun seedGlobal(home: Path) {
        val g = home.resolve(".claude")
        Files.createDirectories(g.resolve("agents"))
        Files.createDirectories(g.resolve("commands"))
        g.resolve("settings.json").writeText("""{"theme":"dark","permissions":{"allow":["Bash"]}}""")
        g.resolve("CLAUDE.md").writeText("global rules")
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"fs":{"command":"x"}},"verbose":true,"theme":"dark","extra":"keepme"}""",
        )
    }

    private fun spec(configDir: Path) =
        MaterializeSpec(configDir, allPolicy, listOf("m1"), "m1", optionsCache, statusline)

    // DR-11 redo (codex mutation-ordering catch): the strict .claude.json read used to run at the
    // END of writeClaudeJson, so a corrupt local aborted AFTER settings.json had already been
    // rewritten — a half-built config. With the read hoisted ahead of every mutation, an abort
    // touches nothing: a settings.json that was a symlink to the operator's global is still that
    // symlink, and the global is byte-identical. RED on the un-hoisted read (settings becomes a
    // real merged file before the abort) — this pins gap 1, and the surviving symlink pins gap 2's
    // "a later failure must not lose the pre-existing settings link".
    @Test
    fun `an abort from a corrupt local claude json leaves settings and its symlink untouched`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val settings = configDir.resolve("settings.json")
        Files.createSymbolicLink(settings, home.resolve(".claude/settings.json"))
        configDir.resolve(".claude.json").writeText("""{"customApiKeyResponses":{"approved":["k1"]}, TRUNCATED""")

        assertThrows(IOException::class.java) {
            ClaudeConfigMaterializer(home).materialize(spec(configDir))
        }

        assertTrue(Files.isSymbolicLink(settings), "a pre-mutation abort must leave the settings symlink untouched")
        assertEquals(
            """{"theme":"dark","permissions":{"allow":["Bash"]}}""",
            home.resolve(".claude/settings.json").readText(),
            "the operator's global settings must never be clobbered through the symlink",
        )
    }

    // DR-11 redo (codex swap-un-pinned catch): the shared-link swap must NEVER destroy the
    // operator's pre-existing file when symlink creation fails (ENOSPC still spends an inode; an
    // LSM can deny it). The staged + atomic-move swap touches only a throwaway staging path, so a
    // create failure leaves dst byte-identical. RED on a delete-then-create swap (the operator file
    // is unlinked before the create that then throws), reproducing the original DR-11a defect.
    @Test
    fun `a failed shared-link swap preserves the operator's pre-existing file`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val victim = configDir.resolve("CLAUDE.md")
        val precious = "OPERATOR CONTENT — must survive a failed swap\n"
        victim.writeText(precious)
        val throwing = SymlinkOp { _, _ -> throw IOException("ENOSPC: no inode for the symlink") }

        ClaudeConfigMaterializer(home, LogSink { }, throwing).materialize(spec(configDir))

        assertEquals(precious, victim.readText(), "a failed symlink swap must leave the operator's file byte-identical")
    }

    // DR-11 redo (codex successful-replacement mutation gap): the failure arms above pin that a FAILED
    // swap preserves the operator's file, but nothing pinned the SUCCESS path — a pre-existing regular
    // file at a shared path must be REPLACED with a symlink into the global. codex's
    // `if (Files.exists(dst, NOFOLLOW_LINKS)) return` early-out in replaceWithSymlink survived every
    // existing test because none asserted the replacement actually happens. RED on that mutant: the
    // stale head copy is left in place, so dst is neither a symlink nor the global content.
    @Test
    fun `a pre-existing regular file at a shared path is replaced with the global symlink`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val dst = configDir.resolve("CLAUDE.md")
        dst.writeText("STALE HEAD COPY — must be replaced by the global link\n")

        ClaudeConfigMaterializer(home).materialize(spec(configDir))

        assertTrue(Files.isSymbolicLink(dst), "a real file where the shared link belongs must become a symlink")
        assertEquals(
            "global rules",
            dst.readText(),
            "the shared link must resolve to the operator's global CLAUDE.md, not the stale head copy",
        )
    }

    // DR-11 redo (codex "cover an existing symlink too"): a stale symlink pointing elsewhere must
    // likewise be repointed at the global — the NOFOLLOW existence check the mutant adds fires for a
    // symlink as well, so this arm independently reds it (the link keeps resolving to the decoy).
    @Test
    fun `a pre-existing symlink at a shared path is repointed at the global`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val decoy = home.resolve("decoy.md")
        decoy.writeText("DECOY — the stale link target")
        val dst = configDir.resolve("CLAUDE.md")
        Files.createSymbolicLink(dst, decoy)

        ClaudeConfigMaterializer(home).materialize(spec(configDir))

        assertEquals(
            "global rules",
            dst.readText(),
            "a stale shared symlink must be repointed at the operator's global, not left on the decoy",
        )
    }

    private fun agentsOnlySpec(configDir: Path) = MaterializeSpec(
        configDir,
        ClaudePolicy(share = setOf("agents"), isolate = emptySet()),
        listOf("m1"),
        "m1",
        optionsCache,
        statusline,
    )

    // DR-39 redo 3 (codex repro): `exists(src, NOFOLLOW)` read an untraversable global .claude as
    // ABSENT, so every shared layer skipped silently — materialize "succeeded" while the head
    // launched without the operator's agents/hooks/skills and nothing named the cause.
    @Test
    fun `an untraversable global claude dir declines the shared layer out loud`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val global = home.resolve(".claude")
        Files.setPosixFilePermissions(global, PosixFilePermissions.fromString("---------"))
        val log = mutableListOf<String>()
        try {
            ClaudeConfigMaterializer(home, log = LogSink { log += it }).materialize(agentsOnlySpec(configDir))
        } finally {
            Files.setPosixFilePermissions(global, PosixFilePermissions.fromString("rwx------"))
        }
        assertTrue(
            log.any { it.contains("'agents' NOT linked") },
            "an unreadable global share must be loud per item: $log",
        )
        assertFalse(
            Files.exists(configDir.resolve("agents"), LinkOption.NOFOLLOW_LINKS),
            "nothing was shared, so nothing may pretend to be",
        )
    }

    // The dangling face: the entry exists (NOFOLLOW attributes read fine) but its target is gone.
    // Mirroring it would ship a broken layer, so it is loud and NOT linked.
    @Test
    fun `a dangling global share entry is loud and never mirrored`(@TempDir home: Path) {
        seedGlobal(home)
        val agents = home.resolve(".claude/agents")
        Files.delete(agents)
        Files.createSymbolicLink(agents, home.resolve(".claude/agents-gone"))
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val log = mutableListOf<String>()

        ClaudeConfigMaterializer(home, log = LogSink { log += it }).materialize(agentsOnlySpec(configDir))

        assertTrue(
            log.any { it.contains("'agents' NOT linked") },
            "a dangling global entry must be loud: $log",
        )
        assertFalse(
            Files.exists(configDir.resolve("agents"), LinkOption.NOFOLLOW_LINKS),
            "a broken layer must not be mirrored into the head",
        )
    }

    // The denominator's quiet member: seedGlobal creates no `skills`, and the policy shares it —
    // genuine absence is the optional share, with no noise and the rest materialized normally.
    @Test
    fun `a genuinely absent global share entry stays a quiet optional skip`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val log = mutableListOf<String>()

        ClaudeConfigMaterializer(home, log = LogSink { log += it }).materialize(spec(configDir))

        assertTrue(log.none { it.contains("'skills'") }, "absence is optional, never a failure: $log")
        assertFalse(Files.exists(configDir.resolve("skills"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(
            Files.isSymbolicLink(configDir.resolve("agents")),
            "present layers still link on the same pass",
        )
    }
}

// DR-64 (sweep 2026-08-31, both seats independently): JsonStateReads carried an exists/symlink
// pre-gate into both modes. Strict read a REAL local .claude.json under an untraversable dir —
// and any symlinked local — as "fresh head, safe to seed" and destroyed operator state; tolerant
// silently blanked unreadable global sources and skipped readable dotfiles symlinks. Own class:
// the safety suite above sits near the LargeClass ceiling. Fixtures duplicated per its header.
class JsonStateReadsSafetyTest {

    private val allPolicy = ClaudePolicy(
        share = setOf("settings", "mcps", "agents", "commands", "skills", "hooks", "plugins", "CLAUDE.md"),
        isolate = emptySet(),
    )
    private val optionsCache: JsonElement = buildJsonObject { put("cache", "codex-models") }
    private val statusline = "\"/usr/bin/curl\" -s :3096/statusline"

    private fun seedGlobal(home: Path) {
        val g = home.resolve(".claude")
        Files.createDirectories(g.resolve("agents"))
        g.resolve("settings.json").writeText("""{"theme":"dark","permissions":{"allow":["Bash"]}}""")
        g.resolve("CLAUDE.md").writeText("global rules")
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"fs":{"command":"x"}},"verbose":true,"theme":"dark","extra":"keepme"}""",
        )
    }

    private fun spec(configDir: Path) =
        MaterializeSpec(configDir, allPolicy, listOf("m1"), "m1", optionsCache, statusline)

    // codex's red repro: a REAL local .claude.json under an untraversable head dir read as absent
    // through the pre-gate, and materialize rebuilt the file over the operator's keys. The
    // SymlinkOp seam restores access AFTER the strict read, so on unfixed code the materialize
    // completes and the overwrite is observable; fixed code aborts before any mutation.
    @Test
    fun `a real local claude json under an untraversable dir aborts before mutation`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val precious = """{"operator_key":"must-survive"}"""
        configDir.resolve(".claude.json").writeText(precious)
        Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("---------"))
        val restoringSeam = SymlinkOp { link, target ->
            Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("rwx------"))
            Files.createSymbolicLink(link, target)
        }
        try {
            assertThrows(IOException::class.java) {
                ClaudeConfigMaterializer(home, LogSink { }, restoringSeam).materialize(spec(configDir))
            }
        } finally {
            Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals(
            precious,
            configDir.resolve(".claude.json").readText(),
            "an indeterminate strict read must abort with the operator's local keys untouched",
        )
    }

    @Test
    fun `a symlinked local claude json aborts instead of replacing the operator's link`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val target = home.resolve("dotfiles-claude.json")
        target.writeText("""{"operator_key":"must-survive"}""")
        val local = configDir.resolve(".claude.json")
        Files.createSymbolicLink(local, target)

        assertThrows(IOException::class.java) {
            ClaudeConfigMaterializer(home).materialize(spec(configDir))
        }

        assertTrue(Files.isSymbolicLink(local), "the operator's link must survive the abort")
        assertEquals("""{"operator_key":"must-survive"}""", target.readText(), "the link target stays untouched")
    }

    @Test
    fun `an unreadable global claude json degrades loud, never silent`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        Files.setPosixFilePermissions(home.resolve(".claude.json"), PosixFilePermissions.fromString("---------"))
        val log = mutableListOf<String>()
        try {
            ClaudeConfigMaterializer(home, log = LogSink { log += it }).materialize(spec(configDir))
        } finally {
            Files.setPosixFilePermissions(home.resolve(".claude.json"), PosixFilePermissions.fromString("rw-------"))
        }
        assertTrue(
            log.any { it.contains("NOT inherited") },
            "an unreadable merge source must leave a trace: $log",
        )
        assertFalse(
            configDir.resolve(".claude.json").readText().contains("mcpServers"),
            "nothing inherited from the unreadable source",
        )
    }

    // codex's third repro, file-level flavor: settings.json itself unreadable while the dir
    // traverses. The old chain (exists pre-gate -> tolerant swallow) read the base as EMPTY and
    // rebuilt the file, silently resetting the operator's saved model; a real settings file the
    // rewrite cannot verify must abort instead. Symlink stays the deliberate EMPTY case and a
    // corrupt-content file still rebuilds (the materializer owns it) — both pinned by neighbors.
    @Test
    fun `an unreadable real settings file aborts instead of being rebuilt over`(@TempDir home: Path) {
        seedGlobal(home)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        val settings = configDir.resolve("settings.json")
        val precious = """{"model":"m-special","operator_key":"must-survive"}"""
        settings.writeText(precious)
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("---------"))
        try {
            assertThrows(IOException::class.java) {
                ClaudeConfigMaterializer(home).materialize(spec(configDir))
            }
        } finally {
            Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(
            precious,
            settings.readText(),
            "an unverifiable real settings file must survive the abort byte-identically",
        )
    }

    // DR-65 (codex security probe, materializer flavor): kotlinx parse exceptions embed a
    // "JSON input:" excerpt, so a malformed local .claude.json leaked its bytes (MCP env values,
    // approved keys) through the strict abort message, and a malformed global leaked through the
    // tolerant degrade log. The abort still names the PATH — only the file's bytes are withheld.
    @Test
    fun `state-file bytes never ride diagnostics - DR-65`(@TempDir home: Path) {
        seedGlobal(home)
        val sentinel = "sk-SENTINEL-LEAK-CANARY"
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)
        configDir.resolve(".claude.json").writeText("""{"mcpServers":{"x":{"env":{"KEY":"$sentinel"""")
        val thrown = assertThrows(IOException::class.java) {
            ClaudeConfigMaterializer(home).materialize(spec(configDir))
        }
        assertTrue(!thrown.message.orEmpty().contains(sentinel), "state bytes in the abort: ${thrown.message}")
        assertTrue(thrown.message.orEmpty().contains(".claude.json"), "the abort still names the file")

        Files.delete(configDir.resolve(".claude.json"))
        home.resolve(".claude.json").writeText("""{"mcpServers":{"x":{"env":{"KEY":"$sentinel"""")
        val log = mutableListOf<String>()
        ClaudeConfigMaterializer(home, log = LogSink { log += it }).materialize(spec(configDir))
        assertTrue(log.any { it.contains("NOT inherited") }, "the tolerant degrade stays loud: $log")
        assertTrue(log.none { it.contains(sentinel) }, "state bytes in the degrade log: $log")
    }

    @Test
    fun `a symlinked global claude json is a readable merge source`(@TempDir home: Path) {
        seedGlobal(home)
        val real = home.resolve("dotfiles-claude.json")
        Files.move(home.resolve(".claude.json"), real)
        Files.createSymbolicLink(home.resolve(".claude.json"), real)
        val configDir = home.resolve(".claude-head")
        Files.createDirectories(configDir)

        ClaudeConfigMaterializer(home).materialize(spec(configDir))

        assertTrue(
            configDir.resolve(".claude.json").readText().contains("\"fs\""),
            "a dotfiles-symlinked global must still feed the mcp inherit",
        )
    }
}
