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
import java.nio.file.Path
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
}
