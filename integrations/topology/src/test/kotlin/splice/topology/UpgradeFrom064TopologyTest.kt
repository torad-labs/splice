// upgrade-compat (Nov 23, 2026): a splice.toml written for 0.6.4, where a head was the only thing that served a provider
// and so said its window, tiers, standing prompt, selector prefix and serving knobs, boots on 0.7.0 as it is, and the
// 0.7.0 gateway serves the provider with what that head says. What should move to the provider is said as a warning
// naming each key and never a value, `splice check-config` exits 0 over it, and what the gateway truly cannot serve
// still refuses boot. Found on a real 0.6.4 laptop: its `[heads.realinfer]` refused every boot and every upgrade.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.prompt.SystemPromptMode
import splice.core.topology.TopologyFindings
import splice.core.topology.gateway.HeadFallbacks
import java.nio.file.Path

/** The standing prompt the 0.6.4 head carries; a value, so no warning may repeat it. */
private const val PROMPT = "Answer as the realinfer team's reviewer."

private const val PROVIDER = """
[providers.realinfer]
dialect = "openai-chat"
base_url = "https://api.realinfer.invalid/v1"
auth = { kind = "api-key", env = "REALINFER_KEY" }

[[providers.realinfer.models]]
id = "glm-5.3"
context_window = 491520

[[providers.realinfer.models]]
id = "glm-5.3-flash"
context_window = 200000
"""

/** `[heads.realinfer]` as a 0.6.4 laptop has it. */
private const val HEAD = """
[heads.realinfer]
provider = "realinfer"
port = 3311
discovery_prefix = "realinfer--"
pinned_model = "glm-5.3"
context_window = 400000
model_slots = { opus = "glm-5.3", sonnet = "glm-5.3", haiku = "glm-5.3-flash" }
system_prompt = "$PROMPT"
system_prompt_mode = "append"

[heads.realinfer.overrides]
maxInflight = "3"

[heads.realinfer.claude]
command = "claude-realinfer"
"""

class UpgradeFrom064TopologyTest {
    @TempDir
    lateinit var dir: Path

    private fun read(text: String): ConfigRead = ConfigFindings.read(text, dir)

    @Test
    fun `a 0 6 4 head that holds its provider's values boots, and each value to move is a warning`() {
        val read = read(PROVIDER + HEAD)
        assertTrue(read is ConfigRead.Ready, "a 0.6.4 file boots on 0.7.0: $read")
        val warnings = (read as ConfigRead.Ready).warnings
        val paths = warnings.map { it.path }.toSet()
        assertEquals(
            setOf(
                "heads.realinfer.discovery_prefix",
                "heads.realinfer.context_window",
                "heads.realinfer.model_slots",
                "heads.realinfer.system_prompt",
                "heads.realinfer.system_prompt_mode",
                "heads.realinfer.overrides.maxInflight",
            ),
            paths,
        )
        assertTrue(warnings.all { it.line != null }, "each says its line: $warnings")
        assertTrue(warnings.all { it.move != null }, "each offers its move: $warnings")
        // The gateway reads the standing prompt as one value, so either warning moves text and mode together.
        val prompt = warnings.single { it.path == "heads.realinfer.system_prompt" }.move
        val mode = warnings.single { it.path == "heads.realinfer.system_prompt_mode" }.move
        assertEquals(listOf("system_prompt", "system_prompt_mode"), prompt?.keys)
        assertEquals(listOf("system_prompt_mode", "system_prompt"), mode?.keys)
        assertTrue(warnings.none { PROMPT in it.text() || "400000" in it.text() }, "no value is said: $warnings")
        val served = warnings.filter { it.path != "heads.realinfer.discovery_prefix" }
        assertTrue(served.all { "the gateway serves the provider with it" in it.message }, "$served")
    }

    @Test
    fun `the gateway serves the provider with what its head says, and a silent sibling head gets none of it`() {
        val quiet = "\n[heads.quiet]\nprovider = \"realinfer\"\nport = 3313\n" +
            "discovery_prefix = \"claude-realinfer--\"\npinned_model = \"glm-5.3-flash\"\n"
        val topology = TopologyLoader.parse(PROVIDER + HEAD + quiet)
        val provider = HeadFallbacks(topology).providers().getValue("realinfer")
        assertEquals(400_000L, provider.contextWindow)
        assertEquals(
            mapOf("opus" to "glm-5.3", "sonnet" to "glm-5.3", "haiku" to "glm-5.3-flash"),
            provider.tierModels,
        )
        assertEquals(PROMPT, provider.systemPrompt)
        assertEquals(SystemPromptMode.APPEND, provider.systemPromptMode)
        assertEquals(mapOf("maxInflight" to "3"), provider.overrides)
        val layers = splice.core.topology.TopologyKnobLayer(topology).layers()
        assertEquals(mapOf("maxInflight" to "3"), layers.own["providers.realinfer"], "the gateway's layer has it")
        // A head reads its own and its provider's own values, never another head's through the gateway's fallback.
        assertNull(layers.inherited["quiet"], "the quiet head inherits no sibling's knob")
        val served = topology.heads.getValue("quiet").servedOn(topology.providers.getValue("realinfer"))
        assertNull(served.contextWindow)
        assertNull(served.systemPrompt)
        assertEquals(emptyMap<String, String>(), served.tierModels())
    }

    @Test
    fun `a provider's own value wins, and heads that disagree leave the gateway the provider's own`() {
        val own = PROVIDER.replace(
            "auth = { kind = \"api-key\", env = \"REALINFER_KEY\" }",
            "auth = { kind = \"api-key\", env = \"REALINFER_KEY\" }\ncontext_window = 300000",
        )
        val twin = HEAD.replace("[heads.realinfer", "[heads.twin").replace("port = 3311", "port = 3312")
            .replace("claude-realinfer", "claude-twin").replace("realinfer--", "twin--")
            .replace("haiku = \"glm-5.3-flash\"", "haiku = \"glm-5.3\"")
        val topology = TopologyLoader.parse(own + HEAD + twin)
        val provider = HeadFallbacks(topology).providers().getValue("realinfer")
        assertEquals(300_000L, provider.contextWindow, "the provider's own window stands")
        assertEquals(emptyMap<String, String>(), provider.tierModels, "two heads with two tier maps give none")
        assertEquals(PROMPT, provider.systemPrompt, "where they agree, the gateway has it")

        val warnings = TopologyFindings.warnings(topology)
        val slots = warnings.single { it.path == "heads.realinfer.model_slots" }
        assertTrue("say different values" in slots.message, slots.message)
        val window = warnings.single { it.path == "heads.realinfer.context_window" }
        assertTrue("says another, which the gateway serves" in window.message, window.message)
        assertNull(window.move, "a value the provider already says is the person's choice, never moved over it")
    }

    @Test
    fun `a head's prompt mode is never moved beside a prompt its provider already says`() {
        val own = PROVIDER.replace(
            "auth = { kind = \"api-key\", env = \"REALINFER_KEY\" }",
            "auth = { kind = \"api-key\", env = \"REALINFER_KEY\" }\nsystem_prompt = \"The provider's own.\"",
        )
        val warnings = TopologyFindings.warnings(TopologyLoader.parse(own + HEAD))
        val prompt = warnings.filter { it.path.startsWith("heads.realinfer.system_prompt") }
        val paths = prompt.map { it.path }.toSet()
        assertEquals(setOf("heads.realinfer.system_prompt", "heads.realinfer.system_prompt_mode"), paths)
        assertTrue(prompt.all { it.move == null }, "half a prompt is never moved over the provider's: $prompt")
    }

    @Test
    fun `a head's replace is never moved to the provider while a sibling appends the same text`() {
        val sibling = "\n[heads.sibling]\nprovider = \"realinfer\"\nport = 3313\n" +
            "discovery_prefix = \"claude-realinfer--\"\npinned_model = \"glm-5.3-flash\"\nsystem_prompt = \"$PROMPT\"\n"
        val head = HEAD.replace("system_prompt_mode = \"append\"", "system_prompt_mode = \"replace\"")
        val warnings = TopologyFindings.warnings(TopologyLoader.parse(PROVIDER + head + sibling))
        val prompt = warnings.filter { it.path.startsWith("heads.realinfer.system_prompt") }
        val paths = prompt.map { it.path }.toSet()
        assertEquals(setOf("heads.realinfer.system_prompt", "heads.realinfer.system_prompt_mode"), paths)
        assertTrue(prompt.all { it.move == null }, "the sibling's append is never turned to replace: $prompt")
        assertTrue(prompt.all { "served another standing prompt" in it.message }, "$prompt")

        val same = sibling + "system_prompt_mode = \"replace\"\n"
        val agreed = TopologyFindings.warnings(TopologyLoader.parse(PROVIDER + head + same))
        val moves = agreed.filter { it.path.startsWith("heads.realinfer.system_prompt") }.map { it.move }
        assertTrue(moves.all { it != null }, "heads served one prompt move it together: $agreed")
    }

    @Test
    fun `heads appending one prompt, one saying so and one leaving it unsaid, move it as the gateway serves it`() {
        val sibling = "\n[heads.sibling]\nprovider = \"realinfer\"\nport = 3313\n" +
            "discovery_prefix = \"claude-realinfer--\"\npinned_model = \"glm-5.3-flash\"\nsystem_prompt = \"$PROMPT\"\n"
        val topology = TopologyLoader.parse(PROVIDER + HEAD + sibling)
        val gateway = HeadFallbacks(topology).providers().getValue("realinfer")
        assertEquals(PROMPT, gateway.systemPrompt, "the heads agree, so the gateway serves their prompt today")
        assertEquals(SystemPromptMode.APPEND, gateway.systemPromptMode ?: SystemPromptMode.APPEND)

        val prompt = TopologyFindings.warnings(topology).filter { ".system_prompt" in it.path }
        val paths = prompt.map { it.path }.toSet()
        val expected = setOf(
            "heads.realinfer.system_prompt",
            "heads.realinfer.system_prompt_mode",
            "heads.sibling.system_prompt",
        )
        assertEquals(expected, paths)
        assertTrue(prompt.all { it.move != null }, "moving it leaves the gateway served the same prompt: $prompt")
    }

    @Test
    fun `a head's mode is never moved beside the prompt the provider says, which the gateway serves as appended`() {
        val own = PROVIDER.replace(
            "auth = { kind = \"api-key\", env = \"REALINFER_KEY\" }",
            "auth = { kind = \"api-key\", env = \"REALINFER_KEY\" }\nsystem_prompt = \"$PROMPT\"",
        )
        val head = HEAD.replace("system_prompt_mode = \"append\"", "system_prompt_mode = \"replace\"")
        val sibling = "\n[heads.sibling]\nprovider = \"realinfer\"\nport = 3313\n" +
            "discovery_prefix = \"claude-realinfer--\"\npinned_model = \"glm-5.3-flash\"\n" +
            "system_prompt = \"$PROMPT\"\nsystem_prompt_mode = \"replace\"\n"
        val topology = TopologyLoader.parse(own + head + sibling)
        val gateway = HeadFallbacks(topology).providers().getValue("realinfer")
        assertEquals(PROMPT, gateway.systemPrompt)
        assertNull(gateway.systemPromptMode, "the gateway appends the provider's prompt today")

        val prompt = TopologyFindings.warnings(topology).filter { ".system_prompt" in it.path }
        val paths = prompt.map { it.path }.toSet()
        assertEquals(setOf("heads.realinfer.system_prompt_mode", "heads.sibling.system_prompt_mode"), paths)
        assertTrue(prompt.all { it.move == null }, "the gateway's append is never turned to replace: $prompt")
        assertTrue(prompt.all { "the gateway serves the part of the standing prompt" in it.message }, "$prompt")
    }

    @Test
    fun `a head's warning names its own line, never one in another provider's multi-line prompt`() {
        val example = "\n[providers.other]\ndialect = \"openai-chat\"\nbase_url = \"https://other.invalid\"\n" +
            "auth = { kind = \"api-key\", env = \"OTHER_KEY\" }\nsystem_prompt = \"\"\"\nA head looks like this:\n" +
            "[heads.realinfer]\nsystem_prompt = \"not this one\"\ncontext_window = 1\n\"\"\"\n\n" +
            "[[providers.other.models]]\nid = \"o\"\ncontext_window = 1000\n"
        val lf = PROVIDER + example + HEAD
        for (text in listOf(lf, lf.replace("\n", "\r\n"))) {
            val read = read(text)
            assertTrue(read is ConfigRead.Ready, "$read")
            val warnings = (read as ConfigRead.Ready).warnings
            val real = mapOf(
                "system_prompt" to "system_prompt = \"$PROMPT\"",
                "context_window" to "context_window = 400000",
            )
            for ((path, said) in real) {
                val line = lf.lines().indexOf(said) + 1
                assertEquals(line, warnings.single { it.path == "heads.realinfer.$path" }.line, "$path in $warnings")
            }
        }
    }

    @Test
    fun `a provider value the gateway cannot serve still refuses boot`() {
        val broken = PROVIDER.replace(
            "auth = { kind = \"api-key\", env = \"REALINFER_KEY\" }",
            "auth = { kind = \"api-key\", env = \"REALINFER_KEY\" }\ncontext_window = 0",
        )
        val read = read(broken + HEAD)
        assertTrue(read is ConfigRead.Refused, "$read")
        val paths = (read as ConfigRead.Refused).findings.map { it.path }
        assertEquals(listOf("providers.realinfer.context_window"), paths)
    }
}
