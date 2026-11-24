// Tests Claude user/project skill and slash-command discovery and ACP initialize/system-init autocomplete wiring.
package splice.provider.antigravity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.provider.antigravity.skills.ClaudeSkillCatalog
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path

internal class AcpSkillsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `discovers user and project skills and commands, honoring precedence, symlinks and user-invocable`() {
        val claudeDir = tmp.resolve("claude-home")
        val project = tmp.resolve("project")
        val externalTarget = tmp.resolve("external-skill")
        writeSkill(
            claudeDir.resolve("skills/user-only"),
            "---\ndescription: User helper\nargument-hint: <file>\n---\nBody\n",
        )
        writeSkill(
            claudeDir.resolve("skills/shared"),
            "---\ndescription: User version\n---\nBody\n",
        )
        writeSkill(
            externalTarget,
            "---\nname: linked-skill\ndescription: >-\n  Multiline folded\n  description\n---\nBody\n",
        )
        Files.createDirectories(claudeDir.resolve("skills"))
        Files.createSymbolicLink(claudeDir.resolve("skills/linked-dir"), externalTarget)
        writeSkill(
            claudeDir.resolve("skills/internal-only"),
            "---\ndescription: Hidden\nuser-invocable: false\n---\nBody\n",
        )
        writeCommand(claudeDir.resolve("commands/eli.md"), "---\ndescription: Talk to Eli\n---\nBody\n")
        writeSkill(
            project.resolve(".claude/skills/shared"),
            "---\ndescription: Project version\n---\nBody\n",
        )
        writeSkill(
            project.resolve(".agents/skills/repo-agent"),
            "---\ndescription: Repo agent skill\n---\nBody\n",
        )
        writeCommand(project.resolve(".claude/commands/deploy.md"), "---\ndescription: Deploy app\n---\nBody\n")

        val catalog = ClaudeSkillCatalog(project, claudeDir, LogSink {}).load()
        val byName = catalog.commands.associateBy { it.name }

        assertEquals("Project version", byName["shared"]?.description)
        assertEquals("User helper", byName["user-only"]?.description)
        assertEquals("<file>", byName["user-only"]?.hint)
        assertEquals("Multiline folded description", byName["linked-skill"]?.description)
        assertEquals("Repo agent skill", byName["repo-agent"]?.description)
        assertEquals("Talk to Eli", byName["eli"]?.description)
        assertEquals("Deploy app", byName["deploy"]?.description)
        assertFalse("internal-only" in byName)
        assertTrue("shared" in catalog.skillNames)
        assertTrue("repo-agent" in catalog.skillNames)
        assertTrue("linked-skill" in catalog.skillNames)
        assertFalse("eli" in catalog.skillNames)
        assertFalse("deploy" in catalog.skillNames)
    }

    @Test
    fun `ACP initialize and system init include discovered skills and commands alongside agy native commands`() {
        val home = tmp.resolve("home")
        val project = tmp.resolve("work")
        Files.createDirectories(project)
        seedSkillsAndCommands(home, project)
        val agent = writeMockAgyAgent(tmp.resolve("agent.sh"))
        val args = listOf(
            "--output-format",
            "stream-json",
            "--model=claude-antigravity--gem-4-neon",
            "--permission-mode=default",
            "--stands-in-for=/home/me/.config/Claude/claude-code/2.1.328/10f24c9cfcbf/claude",
        )
        val env = EnvReader { name -> if (name == "HOME") home.toString() else null }
        val seen = driveSession(agent, project, args, env)

        val initResp = seen.first { it.str("type") == "control_response" }.obj("response").obj("response")
        val commands = initResp.array("commands").map { it.jsonObject }.associateBy { it.str("name").orEmpty() }
        assertEquals("Agy native goal", commands["goal"]?.str("description"))
        assertEquals("Run quality audit", commands["audit"]?.str("description"))
        assertEquals("[--mode skill]", commands["audit"]?.str("argumentHint"))
        assertEquals("Adversarial audit", commands["harden"]?.str("description"))
        assertEquals("Ask Eli", commands["eli"]?.str("description"))

        val sysInit = seen.first { it.str("subtype") == "init" }
        val slash = sysInit.array("slash_commands").map { (it as JsonPrimitive).content }
        val skills = sysInit.array("skills").map { (it as JsonPrimitive).content }
        assertTrue(listOf("goal", "audit", "harden", "eli").all { it in slash }, slash.toString())
        assertTrue(listOf("audit", "harden", "goal").all { it in skills }, skills.toString())
        assertFalse("eli" in skills)
    }

    private fun seedSkillsAndCommands(home: Path, project: Path) {
        writeSkill(
            home.resolve(".claude/skills/audit"),
            "---\ndescription: Run quality audit\nargument-hint: \"[--mode skill]\"\n---\nBody\n",
        )
        writeSkill(home.resolve(".claude/skills/goal"), "---\ndescription: Local shadowed goal\n---\nBody\n")
        writeSkill(project.resolve(".agents/skills/harden"), "---\ndescription: Adversarial audit\n---\nBody\n")
        writeCommand(home.resolve(".claude/commands/eli.md"), "---\ndescription: Ask Eli\n---\nBody\n")
    }

    private fun writeMockAgyAgent(agent: Path): Path {
        val sessionJson =
            """{"sessionId":"s-1","modes":{"currentModeId":"default"},"configOptions":[""" +
                """{"id":"model","currentValue":"gemini-4-neon",""" +
                """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]}]}"""
        val agyCmd = """echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-1","update":""" +
            """{"sessionUpdate":"available_commands_update","availableCommands":[""" +
            """{"name":"goal","description":"Agy native goal","input":{"hint":"<goal>"}}]}}}'"""
        val script = listOf(
            """read l; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'""",
            """read l; $agyCmd; echo '{"jsonrpc":"2.0","id":2,"result":$sessionJson}'""",
            "read l",
            """echo '{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}'""",
            "read l",
        ).joinToString("\n")
        Files.writeString(agent, script)
        return agent
    }

    private fun driveSession(
        agent: Path,
        project: Path,
        args: List<String>,
        env: EnvReader,
    ): List<JsonObject> {
        val toSession = PipedOutputStream()
        val stdin = PipedInputStream(toSession, ACP_SKILLS_PIPE_BYTES)
        val received = Channel<JsonObject>(Channel.UNLIMITED)
        val seen = mutableListOf<JsonObject>()
        val stdout = object : OutputStream() {
            private val buf = ByteArrayOutputStream()

            override fun write(b: Int) {
                if (b != '\n'.code) {
                    buf.write(b)
                    return
                }
                received.trySend(Json.parseToJsonElement(buf.toString(Charsets.UTF_8)).jsonObject)
                buf.reset()
            }
        }
        runBlocking {
            withTimeout(ACP_SKILLS_TIMEOUT_MS) {
                val job = async(Dispatchers.IO) {
                    DesktopAcp(
                        args,
                        project,
                        env,
                        Dispatchers.IO,
                        LogSink {},
                        WallClock { ACP_SKILLS_CLOCK },
                        listOf("sh", agent.toString()),
                    ).run(stdin, stdout)
                }
                val initReq =
                    """{"type":"control_request","request_id":"i-1","request":{"subtype":"initialize"}}"""
                sendLine(toSession, initReq)
                awaitUntil(received, seen) { it.str("type") == "control_response" }
                val userReq = """{"type":"user","message":{"role":"user","content":"hi"},""" +
                    """"parent_tool_use_id":null,"uuid":"u-1"}"""
                sendLine(toSession, userReq)
                awaitUntil(received, seen) { it.str("type") == "result" }
                toSession.close()
                assertEquals(0, job.await())
            }
        }
        return seen
    }

    private fun sendLine(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray())
        out.flush()
    }

    private suspend fun awaitUntil(
        received: Channel<JsonObject>,
        seen: MutableList<JsonObject>,
        done: (JsonObject) -> Boolean,
    ) {
        while (true) {
            val line = received.receive()
            seen += line
            if (done(line)) return
        }
    }

    private fun writeSkill(dir: Path, content: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("SKILL.md"), content)
    }

    private fun writeCommand(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
}

private const val ACP_SKILLS_TIMEOUT_MS = 30_000L
private const val ACP_SKILLS_PIPE_BYTES = 1 shl 16
private const val ACP_SKILLS_CLOCK = 1_795_300_000_000L
