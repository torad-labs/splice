// The skill index a new agy session gets in `_meta` when agy offers it, never on resume: the skills agy cannot find
// itself, a skill linked into the person's own folder by the path where they put it, and a project's skill only when
// it really lies inside the project, by its real path.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class DesktopSkillIndexTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a new agy session that offers it gets the index of skills agy cannot find itself`() {
        val rig = rig()
        val audit = skill(rig.home.resolve(".claude/skills/audit"), "Run an audit\nin depth", "Body")
        skill(rig.project.resolve(".agents/skills/native-one"), "agy finds this itself", "Body")
        rig.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) {
            control("init-1", """{"subtype":"initialize"}""")
            answer("init-1")
        }
        val append = rig.asked("session/new").single().obj("_meta").obj("antigravity").obj("systemPrompt")
            .str("append").orEmpty()
        assertTrue(append.startsWith("Local skills available"), append)
        assertTrue("- audit: Run an audit in depth (${audit.resolve("SKILL.md")})" in append, append)
        assertFalse("native-one" in append, append)
    }

    @Test
    fun `no index goes to agy when it does not offer one or when a session is taken up again`() {
        val silent = rig("silent")
        val declined = rig("declined")
        val quoted = rig("quoted")
        val resumed = rig("resumed")
        listOf(silent, declined, quoted, resumed).forEach {
            skill(it.home.resolve(".claude/skills/audit"), "Audit", "Body")
        }
        silent.run(ScriptedAgy()) { initialize() }
        declined.run(ScriptedAgy(onInitialize = hello(APPEND_DECLINED))) { initialize() }
        quoted.run(ScriptedAgy(onInitialize = hello(APPEND_AS_TEXT))) { initialize() }
        val again = ScriptedAgy(onInitialize = hello(APPEND_OFFERED), onResume = RIG_RESUMED)
        resumed.run(again, extra = listOf("--resume=s-1")) { initialize() }
        assertNull(silent.asked("session/new").single()["_meta"])
        assertNull(declined.asked("session/new").single()["_meta"])
        assertNull(quoted.asked("session/new").single()["_meta"], "only a JSON true offers the append")
        assertNull(resumed.asked("session/resume").single()["_meta"])
        assertEquals(emptyList<JsonObject>(), resumed.asked("session/new"))
    }

    @Test
    fun `a skill linked into the person's own folder is indexed by the path where they put it`() {
        val rig = rig("own")
        val homeSkills = Files.createDirectories(rig.home.resolve(".claude/skills"))
        val plain = Files.createDirectories(homeSkills.resolve("plain"))
        Files.writeString(plain.resolve("SKILL.md"), "# Local menu summary\nPrivate instructions\n")
        val installed = skill(tmp.resolve("toolkit-install/stand-up"), "Daily stand-up", "Toolkit body")
        Files.createSymbolicLink(homeSkills.resolve("stand-up"), installed)
        val leaf = Files.createDirectories(homeSkills.resolve("leaf"))
        val leafTarget = skill(tmp.resolve("toolkit-install/leaf"), "Leaf from a toolkit", "Toolkit body")
        Files.createSymbolicLink(leaf.resolve("SKILL.md"), leafTarget.resolve("SKILL.md"))
        val stowed = rig("stowed")
        skill(tmp.resolve("dotfiles-store/skills/audit"), "Run an audit", "Body")
        Files.createDirectories(stowed.home.resolve(".claude"))
        Files.createSymbolicLink(stowed.home.resolve(".claude/skills"), tmp.resolve("dotfiles-store/skills"))

        var menu: Map<String?, String?> = emptyMap()
        rig.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) {
            control("init-1", """{"subtype":"initialize"}""")
            menu = answer("init-1").obj("response").array("commands").map { it.jsonObject }
                .associate { it.str("name") to it.str("description") }
        }
        stowed.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) { initialize() }

        val put = rig.home.toRealPath().resolve(".claude/skills")
        val append = indexSent(rig)
        assertTrue("- stand-up: Daily stand-up (${put.resolve("stand-up/SKILL.md")})" in append, append)
        assertTrue("- leaf: Leaf from a toolkit (${put.resolve("leaf/SKILL.md")})" in append, append)
        val stowedAt = stowed.home.toRealPath().resolve(".claude/skills/audit/SKILL.md")
        assertTrue("- audit: Run an audit ($stowedAt)" in indexSent(stowed), indexSent(stowed))
        assertEquals("Local menu summary", menu["plain"])
        for (excluded in listOf("Local menu summary", "Private instructions", "toolkit-install")) {
            assertFalse(excluded in append, append)
        }
        assertFalse("dotfiles-store" in indexSent(stowed), indexSent(stowed))
    }

    @Test
    fun `a project skill leading outside the project is not indexed, one inside is named by its real path`() {
        val rig = rig("project")
        val projectSkills = Files.createDirectories(rig.project.resolve(".claude/skills"))
        val escape = skill(tmp.resolve("private-tree/escape"), "External description", "External body")
        Files.createSymbolicLink(projectSkills.resolve("escape"), escape)
        val leaf = Files.createDirectories(projectSkills.resolve("leaf"))
        val leafTarget = skill(tmp.resolve("private-tree/leaf"), "External leaf description", "External leaf body")
        Files.createSymbolicLink(leaf.resolve("SKILL.md"), leafTarget.resolve("SKILL.md"))
        val shared = skill(rig.project.resolve("tools/skills/shared"), "Shared in the repository", "Body")
        Files.createSymbolicLink(projectSkills.resolve("shared"), shared)
        val linkedRoot = rig("linked-root")
        skill(tmp.resolve("private-tree/root/target"), "External root description", "Body")
        Files.createDirectories(linkedRoot.project.resolve(".claude"))
        Files.createSymbolicLink(linkedRoot.project.resolve(".claude/skills"), tmp.resolve("private-tree/root"))
        skill(linkedRoot.home.resolve(".claude/skills/audit"), "Run an audit", "Body")

        var menu: Map<String?, String?> = emptyMap()
        rig.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) {
            control("init-1", """{"subtype":"initialize"}""")
            menu = answer("init-1").obj("response").array("commands").map { it.jsonObject }
                .associate { it.str("name") to it.str("description") }
        }
        linkedRoot.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) { initialize() }

        val append = indexSent(rig)
        assertTrue("- shared: Shared in the repository (${shared.resolve("SKILL.md")})" in append, append)
        assertEquals("External description", menu["escape"], "the person can still type it")
        for (excluded in listOf("External", "private-tree", "escape", "- leaf")) {
            assertFalse(excluded in append, append)
        }
        val rooted = indexSent(linkedRoot)
        assertTrue("- audit: Run an audit" in rooted, rooted)
        assertFalse("target" in rooted || "private-tree" in rooted, rooted)
    }

    @Test
    fun `a toolkit skill linked into both a project and the person's folder is indexed by the person's path`() {
        val rig = rig("both")
        val installed = skill(tmp.resolve("toolkit-install/stand-up"), "Daily stand-up", "Toolkit body")
        val projectSkills = Files.createDirectories(rig.project.resolve(".claude/skills"))
        Files.createSymbolicLink(projectSkills.resolve("stand-up"), installed)
        Files.createSymbolicLink(projectSkills.resolve("alias"), installed)
        val homeSkills = Files.createDirectories(rig.home.resolve(".claude/skills"))
        Files.createSymbolicLink(homeSkills.resolve("stand-up"), installed)

        var menu: Map<String?, String?> = emptyMap()
        rig.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) {
            control("init-1", """{"subtype":"initialize"}""")
            menu = answer("init-1").obj("response").array("commands").map { it.jsonObject }
                .associate { it.str("name") to it.str("description") }
        }

        val append = indexSent(rig)
        val put = rig.home.toRealPath().resolve(".claude/skills/stand-up/SKILL.md")
        assertTrue("- stand-up: Daily stand-up ($put)" in append, append)
        assertEquals("Daily stand-up", menu["stand-up"])
        for (excluded in listOf("toolkit-install", rig.project.toRealPath().toString(), "alias")) {
            assertFalse(excluded in append, append)
        }
    }

    @Test
    fun `a skill agy finds through a link in its own folder stays out of the index`() {
        val rig = rig()
        val outside = skill(tmp.resolve("shared/linked"), "Linked from outside", "Body")
        val native = Files.createDirectories(rig.project.resolve(".agents/skills"))
        Files.createSymbolicLink(native.resolve("linked"), outside)
        val leaf = Files.createDirectories(native.resolve("leaf"))
        val elsewhere = skill(tmp.resolve("shared/leaf-target"), "Leaf linked", "Body").resolve("SKILL.md")
        Files.createSymbolicLink(leaf.resolve("SKILL.md"), elsewhere)
        skill(rig.home.resolve(".claude/skills/audit"), "Run an audit", "Body")
        rig.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) { initialize() }
        val append = rig.asked("session/new").single().obj("_meta").obj("antigravity").obj("systemPrompt")
            .str("append").orEmpty()
        assertTrue("- audit: Run an audit" in append, append)
        assertFalse("linked" in append || "Leaf linked" in append, append)
    }

    @Test
    fun `the index agy opens with lists the skills a reload before initialize found`() {
        val rig = rig()
        val gone = skill(rig.home.resolve(".claude/skills/gone"), "Removed before open", "Body")
        rig.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) {
            Files.delete(gone.resolve("SKILL.md"))
            Files.delete(gone)
            skill(rig.home.resolve(".claude/skills/fresh"), "Added before open", "Body")
            control("reload-1", """{"subtype":"reload_skills"}""")
            answer("reload-1")
            initialize()
        }
        val append = indexSent(rig)
        assertTrue("- fresh: Added before open" in append, append)
        assertFalse("gone" in append, append)
    }

    @Test
    fun `a claude skill that aliases or shares a name with one of agy's own stays out of the index`() {
        val rig = rig()
        val native = skill(rig.project.resolve(".agents/skills/shared"), "agy serves this", "Body")
        val claude = Files.createDirectories(rig.project.resolve(".claude/skills"))
        Files.createSymbolicLink(claude.resolve("shared"), native)
        skill(rig.project.resolve(".agents/skills/twin"), "agy's twin", "Body")
        skill(rig.home.resolve(".claude/skills/twin"), "A user skill named like agy's", "Body")
        skill(rig.home.resolve(".claude/skills/audit"), "Run an audit", "Body")
        rig.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) { initialize() }
        val append = indexSent(rig)
        assertTrue("- audit: Run an audit" in append, append)
        assertFalse("shared" in append || "twin" in append, append)
    }

    private suspend fun AgyDriver.initialize() {
        control("init-1", """{"subtype":"initialize"}""")
        answer("init-1")
    }

    /** The skill index the new agy session was opened with. */
    private fun indexSent(rig: AgyRig): String =
        rig.asked("session/new").single().obj("_meta").obj("antigravity").obj("systemPrompt").str("append").orEmpty()

    /** A rig whose project is a repository of its own, so no folder above the test's shows up as a skill root. */
    private fun rig(name: String = "s"): AgyRig {
        val rig = AgyRig(tmp.resolve(name).also(Files::createDirectories))
        Files.createDirectories(rig.project.resolve(".git"))
        return rig
    }

    /** Writes a SKILL.md in [dir]; the skill's real folder. */
    private fun skill(dir: Path, description: String, body: String): Path {
        Files.createDirectories(dir)
        val front = "description: " + description.replace("\n", "\n  ")
        Files.writeString(dir.resolve("SKILL.md"), "---\n$front\n---\n$body\n")
        return dir.toRealPath()
    }

    private fun hello(capabilities: String) =
        """      say '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":{"protocolVersion":1,""" +
            """"agentCapabilities":{$capabilities}}}'"""

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
}

private const val APPEND_OFFERED = """"_meta":{"antigravity":{"tier":true,"systemPrompt":{"append":true}}}"""
private const val APPEND_DECLINED = """"_meta":{"antigravity":{"systemPrompt":{"append":false}}}"""
private const val APPEND_AS_TEXT = """"_meta":{"antigravity":{"systemPrompt":{"append":"true"}}}"""
