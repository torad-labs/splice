// What a Desktop session on agy does with local skills: a typed `/name args` reaches agy expanded (string and block
// content, images kept, `$ARGUMENTS` filled) while Desktop's replay and the transcript keep what was typed; agy's own
// commands pass through; `reload_skills` finds a new skill; `system/init` is said on `initialize` and not again on the
// first turn; and a new agy session gets the skill index in `_meta` only when agy offers it, never on resume.
package splice.provider.antigravity

import kotlinx.serialization.json.Json
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

internal class DesktopSkillsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a typed skill reaches agy expanded while the replay and transcript keep what was typed`() {
        val rig = rig()
        val folder = skill(rig.home.resolve(".claude/skills/audit"), "Run an audit", "Audit \$ARGUMENTS carefully.")
        val seen = rig.run(ScriptedAgy()) {
            send(user("\"/audit src/a.kt\"", "u-1"))
            result()
        }
        val expected = "<command-name>/audit</command-name>\n<command-args>src/a.kt</command-args>\n" +
            "Base directory for this skill: $folder\n\nAudit src/a.kt carefully."
        assertEquals(listOf(expected), sentText(rig))
        val replay = seen.lines.single { it.str("type") == "user" && it.str("isReplay") == "true" }
        assertEquals("/audit src/a.kt", replay.obj("message").str("content"))
        val firstLine = Files.readAllLines(rig.transcriptFile("s-1")).first()
        assertEquals("/audit src/a.kt", Json.parseToJsonElement(firstLine).jsonObject.obj("message").str("content"))
        seen.raw.forEach { assertFalse("carefully" in it, "Desktop never sees the expanded prompt") }
    }

    @Test
    fun `a command in a message with an image expands its text, appends the arguments and keeps the image`() {
        val rig = rig()
        Files.createDirectories(rig.home.resolve(".claude/commands"))
        Files.writeString(rig.home.resolve(".claude/commands/deploy.md"), "---\ndescription: Deploy\n---\nShip it.\n")
        val blocks = """[{"type":"image","source":{"type":"base64","media_type":"image/png",""" +
            """"data":"iVBORw0KGgo="}},{"type":"text","text":"/deploy prod"}]"""
        rig.run(ScriptedAgy(onInitialize = hello(""""promptCapabilities":{"image":true}"""))) {
            send(user(blocks, "u-1"))
            result()
        }
        val prompt = rig.asked("session/prompt").single().array("prompt").map { it.jsonObject }
        assertEquals(listOf("image", "text"), prompt.map { it.str("type") })
        assertEquals("iVBORw0KGgo=", prompt[0].str("data"))
        val expected = "<command-name>/deploy</command-name>\n<command-args>prod</command-args>\n\n" +
            "Ship it.\n\nARGUMENTS: prod"
        assertEquals(expected, prompt[1].str("text"))
    }

    @Test
    fun `agy's own command of the same name goes to agy as typed`() {
        val rig = rig()
        skill(rig.home.resolve(".claude/skills/goal"), "Local goal", "Local body")
        val nativeGoal = """      upd '{"sessionUpdate":"available_commands_update",""" +
            """"availableCommands":[{"name":"goal","description":"Agy goal","input":{"hint":"<goal>"}}]}'"""
        rig.run(ScriptedAgy(onNew = "$nativeGoal\n$RIG_OPEN_NEW")) {
            send(user("\"/goal ship it\"", "u-1"))
            result()
        }
        assertEquals(listOf("/goal ship it"), sentText(rig))
    }

    @Test
    fun `reload_skills lists a skill added mid-session and its invocation then expands`() {
        val rig = rig()
        var listed = JsonArray(emptyList())
        rig.run(ScriptedAgy()) {
            control("init-1", """{"subtype":"initialize"}""")
            answer("init-1")
            skill(rig.home.resolve(".claude/skills/fresh"), "Fresh skill", "Do the fresh thing.", "<when>")
            control("reload-1", """{"subtype":"reload_skills"}""")
            listed = answer("reload-1").obj("response").array("skills")
            send(user("\"/fresh now\"", "u-1"))
            result()
        }
        val fresh = listed.map { it.jsonObject }.single { it.str("name") == "fresh" }
        assertEquals("Fresh skill", fresh.str("description"))
        assertEquals("<when>", fresh.str("argumentHint"))
        val sent = sentText(rig).single()
        assertTrue(sent.startsWith("<command-name>/fresh</command-name>\n<command-args>now</command-args>\n"), sent)
        assertTrue(sent.endsWith("\n\nDo the fresh thing.\n\nARGUMENTS: now"), sent)
    }

    @Test
    fun `system init is said on initialize, not again on the first turn, and again on the next`() {
        val rig = rig()
        skill(rig.home.resolve(".claude/skills/audit"), "Run an audit", "Body")
        var afterAnswer: JsonObject? = null
        var firstTurn = 0
        val seen = rig.run(ScriptedAgy()) {
            control("init-1", """{"subtype":"initialize"}""")
            answer("init-1")
            afterAnswer = next { true }
            send(user("\"hi\"", "u-1"))
            result()
            firstTurn = this.seen.count { it.str("subtype") == "init" }
            send(user("\"again\"", "u-2"))
            result()
        }
        val init = checkNotNull(afterAnswer)
        assertEquals("init", init.str("subtype"))
        assertEquals(listOf("audit"), init.array("skills").map { (it as JsonPrimitive).content })
        assertTrue("audit" in init.array("slash_commands").map { (it as JsonPrimitive).content })
        assertEquals(1, firstTurn)
        assertEquals(2, seen.lines.count { it.str("subtype") == "init" })
    }

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
        val resumed = rig("resumed")
        listOf(silent, declined, resumed).forEach { skill(it.home.resolve(".claude/skills/audit"), "Audit", "Body") }
        silent.run(ScriptedAgy()) { initialize() }
        declined.run(ScriptedAgy(onInitialize = hello(APPEND_DECLINED))) { initialize() }
        val again = ScriptedAgy(onInitialize = hello(APPEND_OFFERED), onResume = RIG_RESUMED)
        resumed.run(again, extra = listOf("--resume=s-1")) { initialize() }
        assertNull(silent.asked("session/new").single()["_meta"])
        assertNull(declined.asked("session/new").single()["_meta"])
        assertNull(resumed.asked("session/resume").single()["_meta"])
        assertEquals(emptyList<JsonObject>(), resumed.asked("session/new"))
    }

    private suspend fun AgyDriver.initialize() {
        control("init-1", """{"subtype":"initialize"}""")
        answer("init-1")
    }

    /** A rig whose project is a repository of its own, so no folder above the test's shows up as a skill root. */
    private fun rig(name: String = "s"): AgyRig {
        val rig = AgyRig(tmp.resolve(name).also(Files::createDirectories))
        Files.createDirectories(rig.project.resolve(".git"))
        return rig
    }

    /** Writes a SKILL.md in [dir]; the skill's real folder. */
    private fun skill(dir: Path, description: String, body: String, hint: String? = null): Path {
        Files.createDirectories(dir)
        val hinted = hint?.let { "\nargument-hint: \"$it\"" }.orEmpty()
        val front = "description: " + description.replace("\n", "\n  ") + hinted
        Files.writeString(dir.resolve("SKILL.md"), "---\n$front\n---\n$body\n")
        return dir.toRealPath()
    }

    /** The text blocks of each prompt agy was sent. */
    private fun sentText(rig: AgyRig): List<String> = rig.asked("session/prompt").flatMap { params ->
        params.array("prompt").map { it.jsonObject }
            .filter { it.str("type") == "text" }
            .map { it.str("text").orEmpty() }
    }

    private fun user(content: String, uuid: String) =
        """{"type":"user","message":{"role":"user","content":$content},"parent_tool_use_id":null,"uuid":"$uuid"}"""

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
