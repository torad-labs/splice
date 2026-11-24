// Launch profiles' two gaps (issue #791, gap 2): the `.claude.json` a profile's own is seeded from is the one Claude
// Code would read for the person: the file `claude_json` names, else the one in the launching shell's
// CLAUDE_CONFIG_DIR, else `~/.claude.json`. A CLAUDE_CONFIG_DIR that is a profile's or a head's folder is passed over,
// the source is only ever read, and its Claude login never reaches the profile.
package splice.client.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.LaunchProfileConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LaunchProfileStateTest {
    @TempDir lateinit var tmp: Path

    /** The test's home as it really is: a profile's folder is where its links lead (macOS keeps temp under a link). */
    private val home: Path get() = tmp.toRealPath()

    private val claude: Path get() = home.resolve(".claude")
    private val folder: Path get() = home.resolve("profile")
    private val root: Path get() = home.resolve("launch-profiles")
    private val registry = JsonObject(mapOf("registered" to JsonObject(mapOf("command" to JsonPrimitive("reg")))))

    /** A `.claude.json` at [file] with one MCP server and one trusted project, both named [tag], and a Claude login. */
    private fun state(file: Path, tag: String) {
        Files.createDirectories(file.parent)
        file.writeText(
            """{"oauthAccount":{"emailAddress":"$tag@x"},"primaryApiKey":"sk-$tag",
               "projects":{"/$tag":{"hasTrustDialogAccepted":true}},
               "mcpServers":{"$tag":{"command":"$tag","env":{"TOKEN":"secret"}}}}""",
        )
    }

    private fun build(
        inherited: String?,
        profile: LaunchProfileConfig = LaunchProfileConfig(),
        at: Path = folder,
        heads: List<Path> = emptyList(),
    ): ProfileBuilt = LaunchProfileBuild(home, root, registry, heads)
        .build("work", profile.copy(dir = at.toString()), emptySet(), inherited)

    private fun json(path: Path): JsonObject = Json.parseToJsonElement(path.readText()).jsonObject

    private fun servers(at: Path = folder): Set<String> =
        json(at.resolve(".claude.json")).getValue("mcpServers").jsonObject.keys

    private fun projects(at: Path = folder): Set<String> =
        json(at.resolve(".claude.json")).getValue("projects").jsonObject.keys

    @Test
    fun `a shell's CLAUDE_CONFIG_DIR is where the person's MCP servers and projects are read from, login left out`() {
        state(home.resolve(".claude.json"), "home")
        state(claude.resolve(".claude.json"), "dir")
        for ((index, inherited) in listOf(claude.toString(), "~/.claude", "$claude/").withIndex()) {
            val at = home.resolve("p$index")
            assertInstanceOf(ProfileBuilt.Ready::class.java, build(inherited, at = at))
            assertEquals(setOf("dir", "registered"), servers(at), inherited)
            assertEquals(setOf("/dir"), projects(at), inherited)
            val config = json(at.resolve(".claude.json"))
            assertNull(config["oauthAccount"], inherited)
            assertNull(config["primaryApiKey"], inherited)
        }
    }

    @Test
    fun `with no CLAUDE_CONFIG_DIR, or a blank or relative one, the person's ~-claude-json is read`() {
        state(home.resolve(".claude.json"), "home")
        state(claude.resolve(".claude.json"), "dir")
        for ((index, inherited) in listOf(null, "", "  ", ".claude", "relative/dir").withIndex()) {
            val at = home.resolve("p$index")
            assertInstanceOf(ProfileBuilt.Ready::class.java, build(inherited, at = at))
            assertEquals(setOf("home", "registered"), servers(at), inherited.toString())
            assertEquals(setOf("/home"), projects(at), inherited.toString())
        }
    }

    @Test
    fun `a CLAUDE_CONFIG_DIR that is the profile's own folder or another profile's is passed over`() {
        state(home.resolve(".claude.json"), "home")
        build(null)
        state(folder.resolve(".claude.json"), "stale")
        assertInstanceOf(ProfileBuilt.Ready::class.java, build(folder.toString()))
        assertEquals(setOf("home", "registered"), servers())
        val other = home.resolve("other")
        val otherBuilt = LaunchProfileBuild(home, root, registry)
            .build("other", LaunchProfileConfig(dir = other.toString()), emptySet())
        assertInstanceOf(ProfileBuilt.Ready::class.java, otherBuilt)
        state(other.resolve(".claude.json"), "other")
        state(root.resolve("lean/.claude.json"), "lean")
        for (inherited in listOf(other.toString(), root.resolve("lean").toString(), folder.resolve("sub").toString())) {
            assertInstanceOf(ProfileBuilt.Ready::class.java, build(inherited))
            assertEquals(setOf("home", "registered"), servers(), inherited)
        }
    }

    @Test
    fun `a CLAUDE_CONFIG_DIR that is a head's folder is passed over, and ~-claude stays the person's`() {
        state(home.resolve(".claude.json"), "home")
        state(claude.resolve(".claude.json"), "dir")
        val head = home.resolve(".claude-codex")
        state(head.resolve(".claude.json"), "head")
        build(head.toString(), heads = listOf(head, claude))
        assertEquals(setOf("home", "registered"), servers())
        build(claude.toString(), heads = listOf(head, claude))
        assertEquals(setOf("dir", "registered"), servers())
    }

    @Test
    fun `claude_json names the source over the shell's CLAUDE_CONFIG_DIR, absolute or from ~-`() {
        state(home.resolve(".claude.json"), "home")
        state(claude.resolve(".claude.json"), "dir")
        state(home.resolve("elsewhere/state.json"), "named")
        for (named in listOf(home.resolve("elsewhere/state.json").toString(), "~/elsewhere/state.json")) {
            build(claude.toString(), LaunchProfileConfig(claudeJson = named))
            assertEquals(setOf("named", "registered"), servers(), named)
        }
    }

    @Test
    fun `claude_json through a dangling link to the output refuses before creating the profile`() {
        val output = folder.resolve(".claude.json")
        val link = Files.createSymbolicLink(home.resolve("source.json"), home.relativize(output))
        val profile = LaunchProfileConfig(claudeJson = "~/source.json")
        val refused = assertInstanceOf(ProfileBuilt.Refused::class.java, build(null, profile))
        assertTrue("claude_json" in refused.reason && "~/source.json" in refused.reason, refused.reason)
        assertFalse(Files.exists(folder))

        assertInstanceOf(ProfileBuilt.Ready::class.java, build(null))
        state(output, "unchanged")
        val before = output.readText()
        assertInstanceOf(ProfileBuilt.Refused::class.java, build(null, profile))
        assertEquals(before, link.readText())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun `claude_json with a parent step through a link into the profile is refused`() {
        assertInstanceOf(ProfileBuilt.Ready::class.java, build(null))
        val sub = Files.createDirectory(folder.resolve("sub"))
        val link = Files.createSymbolicLink(home.resolve("sub-link"), sub)
        val output = folder.resolve(".claude.json")
        state(output, "unchanged")
        val before = output.readText()
        val profile = LaunchProfileConfig(claudeJson = "$link/../.claude.json")
        val refused = assertInstanceOf(ProfileBuilt.Refused::class.java, build(null, profile))
        assertTrue("claude_json" in refused.reason && "$link/../.claude.json" in refused.reason, refused.reason)
        assertEquals(before, output.readText())
    }

    @Test
    fun `the shell's config folder follows a link before walking back to its parent`() {
        state(home.resolve(".claude.json"), "home")
        val misleading = home.resolve("misleading")
        val personal = home.resolve("personal")
        state(misleading.resolve(".claude.json"), "unrelated")
        state(personal.resolve(".claude.json"), "personal")
        val sub = Files.createDirectory(personal.resolve("sub"))
        val link = Files.createSymbolicLink(misleading.resolve("link"), sub)
        assertInstanceOf(ProfileBuilt.Ready::class.java, build("$link/.."))
        assertEquals(setOf("personal", "registered"), servers())
    }

    @Test
    fun `the shell's config file linking to the profile's output is passed over`() {
        state(home.resolve(".claude.json"), "home")
        assertInstanceOf(ProfileBuilt.Ready::class.java, build(null))
        val output = folder.resolve(".claude.json")
        state(output, "stale")
        Files.createDirectory(claude)
        Files.createSymbolicLink(claude.resolve(".claude.json"), output)
        assertInstanceOf(ProfileBuilt.Ready::class.java, build(claude.toString()))
        assertEquals(setOf("home", "registered"), servers())
    }

    @Test
    fun `the source is only read, and ~-claude is left as it was`() {
        state(home.resolve(".claude.json"), "home")
        state(claude.resolve(".claude.json"), "dir")
        state(home.resolve("elsewhere/state.json"), "named")
        Files.createDirectories(claude.resolve("skills/mine"))
        val before = snapshot(claude)
        val sources = listOf(".claude.json", ".claude/.claude.json", "elsewhere/state.json").map(home::resolve)
        val bytes = sources.map { Files.readAllBytes(it).toList() }
        build(claude.toString())
        build(null)
        build(claude.toString(), LaunchProfileConfig(claudeJson = "~/elsewhere/state.json"))
        assertEquals(before, snapshot(claude))
        assertEquals(bytes, sources.map { Files.readAllBytes(it).toList() })
    }

    @Test
    fun `a CLAUDE_CONFIG_DIR's claude-json that can't be read stops the launch`() {
        state(home.resolve(".claude.json"), "home")
        Files.createDirectories(claude)
        claude.resolve(".claude.json").writeText("{not json")
        assertInstanceOf(ProfileBuilt.Refused::class.java, build(claude.toString()))
    }

    /** Every path under [root] (links as links), with a file's bytes and time. */
    private fun snapshot(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.toList().associate { path ->
            val what = when {
                Files.isSymbolicLink(path) -> "link:" + Files.readSymbolicLink(path)
                Files.isRegularFile(path) -> "file:" + path.readText() + Files.getLastModifiedTime(path)
                else -> "dir"
            }
            root.relativize(path).toString() to what
        }
    }
}
