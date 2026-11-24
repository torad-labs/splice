// Terminal launch profiles (issue #791, comment §4): `[launch.profiles.<name>]` parses as written, and a profile that
// could not run refuses boot at its own key, above all one whose folder is `~/.claude`, inside it, or holds it.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.topology.KEEP_HOOKS_ALL
import splice.core.topology.LaunchProfileConfig
import java.nio.file.Files
import java.nio.file.Path

class LaunchProfilesConfigTest {
    @TempDir lateinit var home: Path

    @TempDir lateinit var directory: Path

    private fun read(toml: String): ConfigRead = UserHome.within(home) { ConfigFindings.read(toml, directory) }

    private fun refusedAt(toml: String): List<String> =
        assertInstanceOf(ConfigRead.Refused::class.java, read(toml)).findings.map { it.path }.sorted()

    @Test
    fun `a profile parses every field as written`() {
        val toml = """
            [launch.profiles.work]
            dir = "~/.claude-work"
            keep_hooks = "all"
            drop_hooks = ["SessionStart", "notify.sh"]
            keep_plugins = ["code-review@official"]
            drop_blocks = ["team-notes"]
            drop_names = ["team-"]
        """.trimIndent()
        val ready = assertInstanceOf(ConfigRead.Ready::class.java, read(toml))
        val expected = LaunchProfileConfig(
            dir = "~/.claude-work",
            keepHooks = KEEP_HOOKS_ALL,
            dropHooks = listOf("SessionStart", "notify.sh"),
            keepPlugins = listOf("code-review@official"),
            dropBlocks = listOf("team-notes"),
            dropNames = listOf("team-"),
        )
        assertEquals(mapOf("work" to expected), ready.topology.launch.profiles)
        val folder = expected.folder("work", home, home.resolve("unused"))
        assertEquals(home.resolve(".claude-work"), folder)
    }

    @Test
    fun `a profile with no dir lives under the root it is given, and keeps the person's own hooks only`() {
        val ready = assertInstanceOf(ConfigRead.Ready::class.java, read("[launch.profiles.lean]\n"))
        val lean = ready.topology.launch.profiles.getValue("lean")
        assertEquals("own", lean.keepHooks)
        assertEquals(emptyList<String>(), lean.keepPlugins)
        assertEquals(home.resolve("root/lean"), lean.folder("lean", home, home.resolve("root")))
    }

    @Test
    fun `a dir that is ~-claude itself refuses boot, however it is spelled`() {
        for (dir in listOf("~/.claude", "~/.claude/", "~/x/../.claude", home.resolve(".claude").toString())) {
            assertEquals(
                listOf("launch.profiles.work.dir"),
                refusedAt("[launch.profiles.work]\ndir = \"$dir\"\n"),
                "dir = $dir",
            )
        }
    }

    @Test
    fun `a dir inside ~-claude, the home holding it, or a relative dir refuses boot`() {
        for (dir in listOf("~/.claude/profiles/work", "~", "~/", "claude-work")) {
            assertEquals(listOf("launch.profiles.work.dir"), refusedAt("[launch.profiles.work]\ndir = \"$dir\"\n"), dir)
        }
    }

    @Test
    fun `a keep_hooks word, a blank entry, a bad marker or prefix and a bad name are each a finding at its key`() {
        val toml = """
            [launch.profiles.work]
            keep_hooks = "some"
            drop_hooks = [" "]
            keep_plugins = [""]
            drop_blocks = ["a -->"]
            drop_names = ["skills/x"]
            [launch.profiles.-bad]
        """.trimIndent()
        val expected = listOf(
            "launch.profiles.-bad",
            "launch.profiles.work.drop_blocks",
            "launch.profiles.work.drop_hooks",
            "launch.profiles.work.drop_names",
            "launch.profiles.work.keep_hooks",
            "launch.profiles.work.keep_plugins",
        )
        assertEquals(expected, refusedAt(toml).distinct())
    }

    @Test
    fun `two profiles on one folder refuse boot at both`() {
        val toml = "[launch.profiles.a]\ndir = \"~/p\"\n[launch.profiles.b]\ndir = \"~/p/\"\n"
        assertEquals(listOf("launch.profiles.a.dir", "launch.profiles.b.dir"), refusedAt(toml))
    }

    @Test
    fun `a profile with no dir is checked where the launch builds it, under splice's own root`() {
        val clash = "[launch.profiles.a]\ndir = \"~/.splice/launch-profiles/b\"\n[launch.profiles.b]\n"
        assertEquals(listOf("launch.profiles.a.dir", "launch.profiles.b.dir"), refusedAt(clash))
        val apart = "[launch.profiles.x]\n[launch.profiles.y]\ndir = \"~/launch-profiles/x\"\n"
        assertInstanceOf(ConfigRead.Ready::class.java, read(apart))
    }

    @Test
    fun `a dir that is ~-gemini, inside it or holds it refuses boot`() {
        for (dir in listOf("~/.gemini", "~/.gemini/work")) {
            assertEquals(listOf("launch.profiles.work.dir"), refusedAt("[launch.profiles.work]\ndir = \"$dir\"\n"), dir)
        }
    }

    @Test
    fun `two profiles that reach one folder through a link refuse boot at both`() {
        Files.createDirectories(home.resolve("real"))
        Files.createSymbolicLink(home.resolve("alias"), home.resolve("real"))
        val toml = "[launch.profiles.a]\ndir = \"~/real\"\n[launch.profiles.b]\ndir = \"~/alias\"\n"
        assertEquals(listOf("launch.profiles.a.dir", "launch.profiles.b.dir"), refusedAt(toml))
    }

    @Test
    fun `a profile with no dir is checked under the state root daemon state_dir names`() {
        val toml = "[daemon]\nstate_dir = \"~/st/state\"\n" +
            "[launch.profiles.a]\ndir = \"~/st/launch-profiles/b\"\n[launch.profiles.b]\n"
        assertEquals(listOf("launch.profiles.a.dir", "launch.profiles.b.dir"), refusedAt(toml))
    }
}
