// What a Desktop session on agy does with local skills: a typed `/name args` reaches agy expanded (string and block
// content, images kept, `$ARGUMENTS` filled) while Desktop's replay and the transcript keep what was typed; agy's own
// commands pass through; `reload_skills` finds a new skill; `system/init` is said on `initialize` and not again on the
// first turn; a peer's message reaches agy as it came, never expanded; and a new agy session gets the skill index in `_meta` only when agy offers it, never on resume.
package splice.provider.antigravity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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
import splice.provider.antigravity.peer.DesktopPeerInbox
import splice.provider.antigravity.peer.DesktopPeerInboxFactory
import splice.provider.antigravity.peer.DesktopPeerMessage
import splice.provider.antigravity.peer.DesktopPeerOutcome
import splice.provider.antigravity.peer.DesktopPeerWiring
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
    fun `a peer's message naming a local skill reaches agy as it came, while a typed one expands`() {
        val rig = rig()
        val folder = skill(rig.home.resolve(".claude/skills/probe"), "Probe it", "Probe \$ARGUMENTS now.")
        val messages = Channel<DesktopPeerMessage>(Channel.UNLIMITED)
        val inbox = object : DesktopPeerInbox {
            override val socketPath: String? = null
            override val pidDomain: String? = null
            override val procStart: String? = null
            override val incoming: ReceiveChannel<DesktopPeerMessage> = messages

            override fun close() {
                messages.close()
            }
        }
        val peer = DesktopPeerWiring(inbox = DesktopPeerInboxFactory { _, _, _, _ -> inbox })
        val delivered = CompletableDeferred<DesktopPeerOutcome>()
        rig.run(ScriptedAgy(), peer = peer) {
            send(user("\"/probe x\"", "u-1"))
            result()
            messages.send(DesktopPeerMessage("/probe peer-args", delivered))
            result()
            assertEquals(DesktopPeerOutcome.Delivered, delivered.await())
        }
        val expanded = "<command-name>/probe</command-name>\n<command-args>x</command-args>\n" +
            "Base directory for this skill: $folder\n\nProbe x now."
        assertEquals(listOf(expanded, "/probe peer-args"), sentText(rig))
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
    fun `reload_skills before initialize leaves agy closed, and initialize still opens it with Desktop's servers`() {
        val rig = rig()
        skill(rig.home.resolve(".claude/skills/audit"), "Run an audit", "Body")
        var listed = JsonArray(emptyList())
        var newBeforeInit = -1
        rig.run(ScriptedAgy()) {
            control("reload-1", """{"subtype":"reload_skills"}""")
            listed = answer("reload-1").obj("response").array("skills")
            newBeforeInit = rig.asked("session/new").size
            control("init-1", """{"subtype":"initialize","mcpServers":{$DOCS_SERVER}}""")
            answer("init-1")
        }
        assertEquals(listOf("audit"), listed.map { it.jsonObject.str("name") })
        assertEquals(0, newBeforeInit)
        val servers = rig.asked("session/new").single().array("mcpServers").map { it.jsonObject.str("name") }
        assertEquals(listOf("docs"), servers)
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

    @Test
    fun `slash login with an image goes to agy as typed even when a local login command exists`() {
        val rig = rig()
        Files.createDirectories(rig.home.resolve(".claude/commands"))
        Files.writeString(rig.home.resolve(".claude/commands/login.md"), "---\ndescription: Mine\n---\nSECRET BODY\n")
        val image = """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}"""
        rig.run(ScriptedAgy(onInitialize = hello(""""promptCapabilities":{"image":true}"""))) {
            send(user("""[{"type":"text","text":"/login now"},$image]""", "u-1"))
            result()
            send(user("""[{"type":"text","text":"/splice:login"},$image]""", "u-2"))
            result()
        }
        assertEquals(listOf("/login now", "/splice:login"), sentText(rig))
    }

    @Test
    fun `a model-only skill is indexed but not typeable, and a person-only skill is typeable but not indexed`() {
        val rig = rig()
        writeFlagged(rig.home.resolve(".claude/skills/hidden"), "Model-only skill", "user-invocable: False # model")
        writeFlagged(rig.home.resolve(".claude/skills/manual"), "Person-only skill", "$NO_MODEL # person only")
        writeFlagged(rig.home.resolve(".claude/skills/neither"), "Nobody's", "user-invocable: false", NO_MODEL)
        var menu = emptyList<String?>()
        var reloaded = emptyList<String?>()
        var initSkills = emptyList<String>()
        rig.run(ScriptedAgy(onInitialize = hello(APPEND_OFFERED))) {
            control("init-1", """{"subtype":"initialize"}""")
            menu = answer("init-1").obj("response").array("commands").map { it.jsonObject.str("name") }
            initSkills = next { it.str("subtype") == "init" }.array("skills").map { (it as JsonPrimitive).content }
            control("reload-1", """{"subtype":"reload_skills"}""")
            reloaded = answer("reload-1").obj("response").array("skills").map { it.jsonObject.str("name") }
            send(user("\"/hidden go\"", "u-1"))
            result()
            send(user("\"/manual go\"", "u-2"))
            result()
        }
        val append = indexSent(rig)
        assertTrue("- hidden: Model-only skill" in append, append)
        assertFalse("manual" in append || "neither" in append, append)
        listOf(menu, reloaded, initSkills).forEach { listed ->
            assertTrue("manual" in listed, listed.toString())
            assertFalse("hidden" in listed || "neither" in listed, listed.toString())
        }
        val sent = sentText(rig)
        assertEquals("/hidden go", sent[0], "a model-only skill is not expanded when typed")
        assertTrue(sent[1].startsWith("<command-name>/manual</command-name>"), sent[1])
    }

    private fun writeFlagged(dir: Path, description: String, vararg flags: String) {
        Files.createDirectories(dir)
        val front = (listOf("description: $description") + flags).joinToString("\n")
        Files.writeString(dir.resolve("SKILL.md"), "---\n$front\n---\nB\n")
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
private const val APPEND_AS_TEXT = """"_meta":{"antigravity":{"systemPrompt":{"append":"true"}}}"""
private const val NO_MODEL = "disable-model-invocation: true"
private const val DOCS_SERVER = """"docs":{"type":"http","url":"https://mcp.example.test/docs"}"""
