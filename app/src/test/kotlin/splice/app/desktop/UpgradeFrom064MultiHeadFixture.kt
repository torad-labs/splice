// The 0.6.4 multi-head home UpgradeFrom064MultiHeadDaemonTest boots: four providers over the three upstream formats
// (Responses for codex and xai, Chat for a loopback team runtime, Anthropic passthrough for kimi), five heads, two of
// them siblings on one provider, a team's managed block between its marker comments, and the values 0.6.4 asked a head
// to hold (window, tiers on its models or as model_slots, standing prompt and mode, maxInflight, a hand-written
// discovery prefix). The fakes each list one model and keep every turn they were sent.
package splice.app.desktop

import splice.core.testing.TestPorts
import java.nio.file.Files
import java.nio.file.Path

/** The team's standing prompt both RealInfer heads append; a value, so no warning may repeat it. */
internal const val MULTI_PROMPT = "Answer as the realinfer team's reviewer."

/** The window both RealInfer heads say; a value, so no warning may repeat it. */
internal const val MULTI_WINDOW = "180000"

/** The comment lines a team's tooling owns its block of splice.toml between. */
internal const val BEGIN_MARK = "# BEGIN team-managed"
internal const val END_MARK = "# END team-managed"

/** One head of the fixture: its key, the fake that serves its provider, the selector of its pinned model under the
 *  head's own prefix, the model id the provider runs it as, the selector the gateway lists it by for a launch, and what
 *  the fake answers. */
internal data class Head(
    val key: String,
    val provider: FormatProvider,
    val selector: String,
    val upstream: String,
    val listed: String,
    val says: String,
)

internal class UpgradeFrom064MultiHeadFixture(private val tmp: Path) {
    val controlPort = TestPorts.reserve()
    val codex = FormatProvider(Format.RESPONSES, "gpt-6-sol", "codex-ok")
    val xai = FormatProvider(Format.RESPONSES, "grok-4.7", "grok-ok")
    val kimi = FormatProvider(Format.MESSAGES, "k3-256k", "kimi-ok")
    val realinfer = FormatProvider(Format.CHAT, "glm-5.3", "realinfer-ok")

    /** Each provider's own key, which only that provider may be sent. */
    val keys = mapOf(
        "codex" to "codex-secret",
        "xai" to "xai-secret",
        "kimi" to "kimi-secret",
        "realinfer" to "ri-secret",
    )

    val heads = listOf(
        Head("claudex", codex, "claude-codex--gpt-6-sol", "gpt-6-sol", "claude-codex--gpt-6-sol", "codex-ok"),
        Head("claude-grok", xai, "claude-grok--grok-4.7", "grok-4.7", "claude-xai--grok-4.7", "grok-ok"),
        Head("claude-kimi", kimi, "claude-kimi--k3-256k", "k3-256k", "claude-kimi--k3-256k", "kimi-ok"),
        Head("claude-realinfer", realinfer, "realinfer--glm-5.3", "glm-5.3", "claude-realinfer--glm-5.3", RI_SAYS),
        Head(
            "claude-realinfer-fast",
            realinfer,
            "realinfer-fast--glm-5.3-flash",
            "glm-5.3-flash",
            "claude-realinfer--glm-5.3-flash",
            RI_SAYS,
        ),
    )

    /** Each head's own port, by its key. */
    private val ports = heads.associate { it.key to TestPorts.reserve() }

    /** Every head's port, in [heads]' order. */
    val headPorts: List<Int> get() = heads.map(::portOf)

    fun portOf(head: Head): Int = ports.getValue(head.key)

    private fun port(key: String): Int = ports.getValue(key)

    fun providerKeyOf(provider: FormatProvider): String = when (provider) {
        codex -> "codex"
        xai -> "xai"
        kimi -> "kimi"
        else -> "realinfer"
    }

    fun stop() = listOf(codex, xai, kimi, realinfer).forEach(FormatProvider::stop)

    /** The 0.6.4 file. */
    fun toml(): String {
        val files = keys.mapValues { (name, key) ->
            tmp.resolve("$name.json").also { Files.writeString(it, """{"api_key":"$key"}""") }.toString()
        }
        return buildString {
            appendLine("[daemon]\ncontrol_port = $controlPort\n")
            appendLine(provider("codex", "openai-responses", codex.baseUrl, files, listOf("gpt-6-sol" to "272000")))
            appendLine(provider("xai", "openai-responses", xai.baseUrl, files, listOf("grok-4.7" to "500000")))
            appendLine(provider("kimi", "anthropic-passthrough", kimi.baseUrl, files, listOf("k3-256k" to "262144")))
            val rows = listOf("glm-5.3" to "200000", "glm-5.3-flash" to "200000")
            appendLine(provider("realinfer", "openai-chat", realinfer.baseUrl, files, rows))
            appendLine(subscriptionHeads())
            appendLine(realinferHeads())
        }
    }

    private fun provider(
        name: String,
        dialect: String,
        base: String,
        files: Map<String, String>,
        rows: List<Pair<String, String>>,
    ): String = buildString {
        appendLine("[providers.$name]\ndialect = \"$dialect\"\nbase_url = \"$base\"")
        appendLine("auth = { kind = \"api-key\", file = \"${files.getValue(name).replace("\\", "/")}\" }")
        for ((id, window) in rows) appendLine("[[providers.$name.models]]\nid = \"$id\"\ncontext_window = $window")
    }

    /** The three heads 0.6.4's example shipped, under their built-in prefixes, claudex holding its tiers on its model
     *  rows, its window and a knob. */
    private fun subscriptionHeads(): String = """
        [heads.claudex]
        provider = "codex"
        port = ${port("claudex")}
        discovery_prefix = "claude-codex--"
        pinned_model = "gpt-6-sol"
        models = [{ id = "gpt-6-sol", slot = "opus" }]
        context_window = 272000
        [heads.claudex.overrides]
        maxInflight = "100"
        [heads.claudex.claude]
        command = "claudex"

        [heads.claude-grok]
        provider = "xai"
        port = ${port("claude-grok")}
        discovery_prefix = "claude-grok--"
        pinned_model = "grok-4.7"
        [heads.claude-grok.claude]
        command = "claude-grok"

        [heads.claude-kimi]
        provider = "kimi"
        port = ${port("claude-kimi")}
        discovery_prefix = "claude-kimi--"
        pinned_model = "k3-256k"
        [heads.claude-kimi.claude]
        command = "claude-kimi"
    """.trimIndent() + "\n"

    /** The team's two heads on one provider: the main one in the managed block, holding the team's window, tiers,
     *  prompt and knob under a hand-written prefix; its sibling on another pin and prefix, saying the same window,
     *  tiers, prompt and knob, as a team that shares them across its heads does. */
    private fun realinferHeads(): String = """
        $BEGIN_MARK
        [heads.claude-realinfer]
        provider = "realinfer"
        port = ${port("claude-realinfer")}
        discovery_prefix = "realinfer--"
        pinned_model = "glm-5.3"
        context_window = $MULTI_WINDOW
        model_slots = { opus = "glm-5.3", sonnet = "glm-5.3", haiku = "glm-5.3-flash" }
        system_prompt = "$MULTI_PROMPT"
        system_prompt_mode = "append"
        [heads.claude-realinfer.overrides]
        maxInflight = "3"
        [heads.claude-realinfer.claude]
        command = "claude-realinfer"
        $END_MARK

        [heads.claude-realinfer-fast]
        provider = "realinfer"
        port = ${port(SIBLING)}
        discovery_prefix = "realinfer-fast--"
        pinned_model = "glm-5.3-flash"
        context_window = $MULTI_WINDOW
        model_slots = { opus = "glm-5.3", sonnet = "glm-5.3", haiku = "glm-5.3-flash" }
        system_prompt = "$MULTI_PROMPT"
        system_prompt_mode = "append"
        [heads.claude-realinfer-fast.overrides]
        maxInflight = "3"
        [heads.claude-realinfer-fast.claude]
        command = "claude-realinfer-fast"
    """.trimIndent() + "\n"
}

private const val RI_SAYS = "realinfer-ok"
