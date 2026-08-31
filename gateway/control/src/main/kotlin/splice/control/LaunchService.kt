// PORT-OF: server/launcher/{assemble-env,ensure-proxy}.mjs exec-recipe @ pre-public-port-baseline, as a daemon
// endpoint (P4-LAUNCH). The bin shim POSTs /launch{head}; the daemon materializes the head's Claude
// config (P5-PREP) and returns the exec recipe. Env recipe restored to Node fidelity — the minimal
// version broke two things: (1) Claude Code fell back to Anthropic /login because it saw a custom
// ANTHROPIC_API_KEY instead of an ANTHROPIC_AUTH_TOKEN bearer; (2) only the pinned model showed
// because CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY wasn't set, so the /model picker never queried
// /v1/models. The bearer stays. Discovery was RETIRED (2026-08-30): the materialized roster —
// settings.json availableModels + .claude.json additionalModelOptionsCache, both from the head's
// selected catalog — is the picker's ONE source now, all bare ids. Discovery re-served the same
// models under wrapped /v1/models ids, so every picker row appeared twice, and a wrapped ACTIVE id
// makes Claude Code ignore CLAUDE_CODE_MAX_CONTEXT_TOKENS (ab5ca6b: honored for unwrapped names
// only), which per-head context windows depend on.
package splice.control

import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.MaterializeSpec
import splice.core.util.EnvReader
import kotlin.math.max

// Floor for CLAUDE_CODE_AUTO_COMPACT_WINDOW (buildEnv): a small context window must not shrink the
// auto-compact window below this.
private const val AUTO_COMPACT_FLOOR = 60_000

// LaunchSpec + LaunchRecipe live in LaunchTypes.kt (concentration, 2026-08-19).

public class LaunchService(
    private val materializer: ClaudeConfigMaterializer,
    private val claudeBinary: String = "claude",
    private val envReader: EnvReader = EnvReader(System::getenv),
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
                signInViaBrowser = spec.signInViaBrowser,
                tokenCapture = spec.tokenCapture,
                advertiseKeySetup = spec.advertiseKeySetup,
                loginOutcomeFile = spec.loginOutcomeFile,
            ),
        )
        val env = buildEnv(spec)
        // Clear anything ambient that would override the proxy or a stale Anthropic session —
        // EXCEPT on a native-auth head, where those variables ARE the credential being forwarded.
        val unset = if (spec.forwardClientAuth) {
            emptyList()
        } else {
            listOf(
                "ANTHROPIC_API_KEY",
                "CLAUDE_CODE_OAUTH_TOKEN",
                "CLAUDE_CODE_OAUTH_REFRESH_TOKEN",
            )
        }
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
            // consuming work. A native-auth head plants NOTHING: the client's own credential must
            // reach the head untouched, and this would override it.
            if (!spec.forwardClientAuth) put("ANTHROPIC_AUTH_TOKEN", spec.inferenceToken)
            put("CLAUDE_CONFIG_DIR", spec.configDir.toString())
            // NO gateway model discovery: the picker reads the materialized roster (settings.json
            // availableModels + .claude.json additionalModelOptionsCache) — see the header for why
            // the wrapped /v1/models spelling must never reach the picker.
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
            //
            // A native-auth head keeps BOTH: its upstream really is Anthropic, so /login is a live
            // door and the only one that can heal a rejected credential — splice runs no sign-in
            // flow of its own for this head precisely because the client's still works.
            if (!spec.forwardClientAuth) {
                put("DISABLE_LOGIN_COMMAND", "1")
                put("DISABLE_LOGOUT_COMMAND", "1")
            }
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

    // opus/sonnet/haiku/fable → Claude Code's tier env slots.
    //
    // A head that DECLARES slots gets EXACTLY its declared tiers and nothing else (2026-08-30):
    // the positional scheme maps four slots onto catalog order, so a roster without sol/terra/luna
    // names lands two models in four slots — the picker then shows the same names repeatedly, and
    // the catalog's ORDER becomes load-bearing (splice.toml still carries a banner saying so).
    // Filling the UNDECLARED tiers positionally just re-created the duplication on any roster
    // smaller than four (a 2-model head still planted one model in 3 slots — codex redo verdict),
    // so declaring anything retires positional order outright: a tier the head does not declare is
    // not emitted, never pointed at an already-claimed model.
    private fun aliasSlots(spec: LaunchSpec): List<Pair<String, String>> {
        val ids = (listOf(spec.pinnedModel) + spec.availableModelIds).distinct()
        val declared = declaredSlots(spec, ids)
        if (declared.isNotEmpty()) {
            return listOf("OPUS", "SONNET", "HAIKU", "FABLE").mapNotNull { slot ->
                declared[slot.lowercase()]?.let { model -> slot to model }
            }
        }
        val fallback = positionalTiers(ids)
        return listOf(
            "OPUS" to fallback.frontier,
            "SONNET" to fallback.mid,
            "HAIKU" to fallback.fast,
            // Fable shares the frontier (opus) slot — positional at(2) used to park it on luna
            // whenever sol/terra/luna were listed in that order.
            "FABLE" to fallback.frontier,
        )
    }

    private data class PositionalTiers(val frontier: String, val mid: String, val fast: String)

    /** The pre-slot heuristic, byte-identical for a head that declares nothing (every head that
     *  existed before slots): Codex 5.6 tier names when the catalog carries them, else catalog
     *  order, with haiku preferring a mini/fast id. [ids] starts with the pinned model, so
     *  `ids.first()` is the never-empty floor. */
    private fun positionalTiers(ids: List<String>): PositionalTiers {
        fun named(tier: String): String? =
            ids.firstOrNull { id ->
                val tail = id.substringAfterLast('-', missingDelimiterValue = id)
                tail.equals(tier, ignoreCase = true)
            }

        val miniOrFast = ids.firstOrNull {
            it.contains("mini", ignoreCase = true) || it.contains("fast", ignoreCase = true)
        }
        return PositionalTiers(
            frontier = named("sol") ?: ids.first(),
            mid = named("terra") ?: ids.getOrNull(1) ?: ids.first(),
            fast = named("luna") ?: miniOrFast ?: ids.getOrNull(1) ?: ids.first(),
        )
    }

    /** slot name -> model id, keeping only slots this catalog actually offers. A declared slot
     *  naming a model the head does not serve is ignored rather than planted, so a stale row in
     *  splice.toml cannot point a tier at a model every turn would 400 on. */
    private fun declaredSlots(spec: LaunchSpec, ids: List<String>): Map<String, String> =
        spec.modelSlots
            .filterKeys { it in ids }
            .entries
            .associate { (model, slot) -> slot.lowercase() to model }
}
