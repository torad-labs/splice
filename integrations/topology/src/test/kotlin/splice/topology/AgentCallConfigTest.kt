// A capability the model calls (docs/specs/harness.md, decisions 1 and 12): `call = "agent"` or `"both"` boots with a
// `tool`, and what does not fit it is refused at its key, every finding at once.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.capability.CapabilityCaller
import splice.core.capability.CapabilityTool
import java.nio.file.Path

class AgentCallConfigTest {
    @TempDir lateinit var directory: Path

    /** `astra` passes Anthropic's Messages through, `boxy` speaks OpenAI's chat; then one capability's [lines]. */
    private fun file(lines: String): String =
        head("astra", "anthropic-passthrough") + head("boxy", "openai-chat") +
            "[capabilities.advisor]\n" + lines.trimIndent() + "\n" + WORK

    private fun head(key: String, dialect: String): String =
        """
        [providers.$key]
        dialect = "$dialect"
        base_url = "http://127.0.0.1:1/v1"
        auth = { kind = "api-key", env = "BOX_KEY" }

        [[providers.$key.models]]
        id = "large"
        label = "large"
        context_window = 131072

        [heads.$key]
        provider = "$key"
        port = ${if (key == "astra") 3191 else 3192}
        discovery_prefix = "claude-$key--"
        pinned_model = "large"
        """.trimIndent() + "\n\n"

    private fun refused(lines: String): Map<String, String> {
        val read = ConfigFindings.read(file(lines), directory, configDir = directory)
        return assertInstanceOf(ConfigRead.Refused::class.java, read, read.toString())
            .findings.filter { it.path.startsWith("capabilities.advisor") }
            .associate { it.path.removePrefix("capabilities.advisor.") to it.toString() }
    }

    @Test
    fun `call agent with a tool boots, and the model is offered the tool as declared`() {
        val toml = file("heads = [\"astra\"]\ncall = \"agent\"\n$TOOL")
        val read = ConfigFindings.read(toml, directory, configDir = directory)
        val capability = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString())
            .topology.capabilities.single()
        assertEquals(CapabilityCaller.AGENT, capability.call.caller)
        val tool = assertInstanceOf(CapabilityTool::class.java, capability.call.tool)
        assertEquals("ask_advisor", tool.name)
        assertEquals("Ask the advisor.", tool.description)
        assertEquals(setOf("type", "properties"), tool.input.keys)
    }

    @Test
    fun `agent or both with no tool is refused at tool`() {
        listOf("agent", "both").forEach { call ->
            val findings = refused("heads = [\"astra\"]\ncall = \"$call\"")
            assertTrue("tool" in findings, "$call: $findings")
        }
    }

    @Test
    fun `a tool on a capability its rule calls is refused at tool`() {
        val findings = refused("heads = [\"astra\"]\ncall = \"rule\"\n$TOOL")
        assertTrue("tool" in findings, findings.toString())
    }

    @Test
    fun `call agent naming a place or a turn is refused at each, at once`() {
        val findings =
            refused("heads = [\"astra\"]\ncall = \"agent\"\nplace = \"system\"\nwhen = { turn = \"first\" }\n$TOOL")
        assertTrue("place" in findings, findings.toString())
        assertTrue("when.turn" in findings, findings.toString())
    }

    @Test
    fun `call agent on a head that does not speak Anthropic's Messages is refused naming the head`() {
        val findings = refused("heads = [\"astra\", \"boxy\"]\ncall = \"both\"\n$TOOL")
        val heads = findings["heads"] ?: error("no finding at heads: $findings")
        assertTrue("boxy" in heads, heads)
        assertTrue("astra" !in heads, heads)
    }
}

private const val TOOL =
    "tool = { name = \"ask_advisor\", description = \"Ask the advisor.\", " +
        "input = { type = \"object\", properties = { question = { type = \"string\" } } } }"

private const val WORK = "message = \"Asking the advisor\"\ncolor = \"magenta\"\n" +
    "worker = { kind = \"http\", url = \"http://127.0.0.1:1/work\" }\n"
