// PORT-OF: server/launcher/{assemble-env,ensure-proxy}.mjs exec-recipe @ pre-public-port-baseline, as a daemon
// endpoint (P4-LAUNCH). The bin shim POSTs /launch{head}; the daemon materializes the head's Claude
// config (P5-PREP) and returns the exec recipe. Env recipe restored to Node fidelity — the minimal
// version broke two things: (1) Claude Code fell back to Anthropic /login because it saw a custom
// ANTHROPIC_API_KEY instead of an ANTHROPIC_AUTH_TOKEN bearer; (2) only the pinned model showed
// because CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY wasn't set, so the /model picker never queried
// /v1/models. Both are set here now, plus the alias slots, context sizing, and the loopback NO_PROXY.
package splice.control

import kotlinx.serialization.json.JsonElement
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.launch.MaterializeSpec
import java.nio.file.Path
import kotlin.math.max

/** What a head needs to produce a launch recipe (supplied by :app at wiring time). */
public data class LaunchSpec(
    val configDir: Path,
    val pinnedModel: String,
    val availableModelIds: List<String>,
    val modelLabels: Map<String, String>, // id -> display label (for the alias slot names)
    val contextWindow: Int,
    val modelOptionsCache: JsonElement, // the /model picker option list
    val statuslineCommand: String, // per-head statusline command (…/statusline/<head>)
    val loginCommand: String, // shell command that runs THIS head's provider sign-in (e.g. `claudex login`)
    val signInLabel: String, // provider label for the /login UX ("Codex (ChatGPT)", "Grok (xAI)")
    val policy: ClaudePolicy,
    val port: Int,
    /** Per-install local gateway credential; shared with the head's inbound verifier. */
    val inferenceToken: String,
)

public data class LaunchRecipe(
    val env: Map<String, String>,
    val unset: List<String>,
    val argv: List<String>,
    // Non-null only when dangerouslySkipPermissions was engaged — surfaced to the operator via the
    // control log and the /launch response so the danger is never silent.
    val warning: String? = null,
)

public class LaunchService(
    private val materializer: ClaudeConfigMaterializer,
    private val claudeBinary: String = "claude",
    private val envReader: (String) -> String? = System::getenv,
) {
    /** Materialize the head's config + build the exec recipe. Safe by default: the flag is added
     *  ONLY when [dangerouslySkipPermissions] is true, and doing so returns a non-null warning. */
    public fun launch(
        spec: LaunchSpec,
        extraArgs: List<String>,
        dangerouslySkipPermissions: Boolean,
    ): LaunchRecipe {
        materializer.materialize(
            MaterializeSpec(
                configDir = spec.configDir,
                policy = spec.policy,
                availableModelIds = spec.availableModelIds,
                defaultModel = spec.pinnedModel,
                modelOptionsCache = spec.modelOptionsCache,
                statuslineCommand = spec.statuslineCommand,
                loginCommand = spec.loginCommand,
                signInLabel = spec.signInLabel,
            ),
        )
        val env = buildEnv(spec)
        // Clear anything ambient that would override the proxy or a stale Anthropic session.
        val unset = listOf(
            "ANTHROPIC_API_KEY",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "CLAUDE_CODE_OAUTH_REFRESH_TOKEN",
        )
        val argv = buildList {
            add(claudeBinary)
            if (dangerouslySkipPermissions) add("--dangerously-skip-permissions")
            // NB: no --model — the active model is ANTHROPIC_MODEL + settings.json, so the /model
            // picker (populated by gateway discovery) can freely switch. Forcing --model locked it.
            addAll(extraArgs)
        }
        val warning = if (dangerouslySkipPermissions) {
            "dangerouslySkipPermissions engaged for ${spec.configDir} — Claude Code runs with " +
                "--dangerously-skip-permissions (no permission prompts)."
        } else {
            null
        }
        return LaunchRecipe(env, unset, argv, warning)
    }

    private fun buildEnv(spec: LaunchSpec): Map<String, String> {
        val slots = aliasSlots(spec)
        return buildMap {
            put("ANTHROPIC_BASE_URL", "http://127.0.0.1:${spec.port}")
            // AUTH_TOKEN (bearer), NOT API_KEY — a bearer avoids Claude Code's custom-api-key
            // approval flow. The head validates this per-install credential before any quota-
            // consuming work.
            put("ANTHROPIC_AUTH_TOKEN", spec.inferenceToken)
            put("CLAUDE_CONFIG_DIR", spec.configDir.toString())
            // THE fix for "only one model shows": lets the /model picker list every /v1/models id.
            put("CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY", "1")
            put("ANTHROPIC_MODEL", spec.pinnedModel)
            slots.forEach { (slot, model) ->
                put("ANTHROPIC_DEFAULT_${slot}_MODEL", model)
                val label = spec.modelLabels[model] ?: model
                put("ANTHROPIC_DEFAULT_${slot}_MODEL_NAME", label)
                put("ANTHROPIC_DEFAULT_${slot}_MODEL_DESCRIPTION", label)
            }
            put("CLAUDE_CODE_MAX_CONTEXT_TOKENS", spec.contextWindow.toString())
            put("CLAUDE_CODE_AUTO_COMPACT_WINDOW", max(AUTO_COMPACT_FLOOR, spec.contextWindow).toString())
            put("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE", "85")
            put("MAX_THINKING_TOKENS", "128000")
            put("NO_PROXY", mergedNoProxy())
            // Hide Claude Code's built-in Anthropic-account commands: in a gateway head, auth is the
            // proxy bearer above, so /login (a local-jsx command hardwired to platform.claude.com —
            // no hook or base-url override can reach it) and /logout are dead doors. These are the
            // CLI's own boolean env flags (Pe.bool over process.env), so the commands never register.
            put("DISABLE_LOGIN_COMMAND", "1")
            put("DISABLE_LOGOUT_COMMAND", "1")
            put("SPLICE", "1")
        }
    }

    private fun mergedNoProxy(): String =
        sequenceOf(envReader("NO_PROXY"), envReader("no_proxy"), "127.0.0.1,localhost")
            .filterNotNull()
            .flatMap { it.split(',').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")

    // opus/sonnet/haiku/fable → distinct models when the catalog offers them (else the pinned one).
    // haiku is the "fast" tier, so it prefers a mini/fast model rather than whatever sorts last.
    private fun aliasSlots(spec: LaunchSpec): List<Pair<String, String>> {
        val ids = (listOf(spec.pinnedModel) + spec.availableModelIds).distinct()
        fun at(i: Int) = ids.getOrElse(i) { spec.pinnedModel }
        val fast = ids.firstOrNull { it.contains("mini") || it.contains("fast") } ?: at(1)
        return listOf(
            "OPUS" to ids.first(),
            "SONNET" to at(1),
            "HAIKU" to fast,
            "FABLE" to at(2),
        )
    }

    private companion object {
        const val AUTO_COMPACT_FLOOR = 60_000
    }
}
