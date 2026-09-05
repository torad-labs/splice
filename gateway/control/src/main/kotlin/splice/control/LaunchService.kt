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

// Floor for CLAUDE_CODE_AUTO_COMPACT_WINDOW (buildEnv): a small client window must not shrink the
// auto-compact window below this. Moot for a real launch since the client window became a constant
// 1e6 (LaunchSpec.contextWindow), kept so a synthetic spec cannot plant a nonsense cap.
private const val AUTO_COMPACT_FLOOR = 60_000L

// LaunchSpec + LaunchRecipe live in LaunchTypes.kt (concentration, 2026-08-19).

public class LaunchService(
    private val materializer: ClaudeConfigMaterializer,
    private val claudeBinary: String = "claude",
    private val envReader: EnvReader = EnvReader(System::getenv),
) {
    /** Materialize the head's config + build the exec recipe. Safe by default: the flag is added
     *  ONLY when [dangerouslySkipPermissions] is true, and doing so returns a non-null warning.
     *
     *  [keyPresentNow] (DR-81) is the LAUNCH-time key-presence read (ManagedHead.keyPresence).
     *  The spec is assembled once at boot and carries the capture capability ungated; whether the
     *  paste-your-key hook and advertiser materialize is decided here, per launch — `splice key
     *  set` promises live pickup, and a present key must disarm both (an accidental paste would
     *  silently OVERWRITE the working credential — review of #75). */
    public fun launch(
        spec: LaunchSpec,
        extraArgs: List<String>,
        dangerouslySkipPermissions: Boolean,
        keyPresentNow: Boolean = true,
    ): LaunchRecipe {
        val effective = if (keyPresentNow) spec.copy(tokenCapture = null, advertiseKeySetup = false) else spec
        val slots = aliasSlots(effective)
        materializer.materialize(
            MaterializeSpec(
                configDir = effective.configDir,
                policy = effective.policy,
                availableModelIds = effective.availableModelIds,
                defaultModel = effective.pinnedModel,
                modelOptionsCache = effective.modelOptionsCache,
                statuslineCommand = effective.statuslineCommand,
                loginCommand = effective.loginCommand,
                signInLabel = effective.signInLabel,
                signInViaBrowser = effective.signInViaBrowser,
                tokenCapture = effective.tokenCapture,
                advertiseKeySetup = effective.advertiseKeySetup,
                loginOutcomeFile = effective.loginOutcomeFile,
            ),
        )
        val env = buildEnv(effective, slots)
        val unset = staleEnvUnsets(effective, slots)
        val argv = buildList {
            add(claudeBinary)
            if (dangerouslySkipPermissions) add("--dangerously-skip-permissions")
            // NB: no --model — the active model is ANTHROPIC_MODEL + settings.json, so the /model
            // picker (populated by the materialized bare-id roster) can freely switch. Forcing it locked the row.
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

    /** Vars a launched head must SCRUB from the inherited environment: bin/splice-launch execs
     *  `env` WITHOUT -i, so a head launched from inside another head's session inherits the OUTER
     *  recipe (the same mechanism that let the mgmt key reach a native head — DR-30). Three
     *  classes: (1) a foreign head strips the client's Anthropic session; a native head keeps it —
     *  those variables ARE the credential being forwarded. (2) Gateway model discovery, retired:
     *  an ambient =1 would re-add the wrapped /v1/models spelling this recipe keeps out of the
     *  picker. (3) Every alias-tier triplet this head does NOT emit — an explicit-slots head that
     *  omits a tier must not let the outer head's value leak through and point that tier at a
     *  model this head cannot serve (codex redo verdict, 2026-08-30). */
    private fun staleEnvUnsets(spec: LaunchSpec, slots: List<Pair<String, String>>): List<String> {
        val auth = if (spec.forwardClientAuth) {
            emptyList()
        } else {
            listOf(
                "ANTHROPIC_API_KEY",
                "CLAUDE_CODE_OAUTH_TOKEN",
                "CLAUDE_CODE_OAUTH_REFRESH_TOKEN",
            )
        }
        val emitted = slots.map { it.first }.toSet()
        val absentTiers = listOf("OPUS", "SONNET", "HAIKU", "FABLE")
            .filterNot { it in emitted }
            .flatMap { tier ->
                listOf(
                    "ANTHROPIC_DEFAULT_${tier}_MODEL",
                    "ANTHROPIC_DEFAULT_${tier}_MODEL_NAME",
                    "ANTHROPIC_DEFAULT_${tier}_MODEL_DESCRIPTION",
                )
            }
        return auth + "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY" + absentTiers
    }

    private fun buildEnv(spec: LaunchSpec, slots: List<Pair<String, String>>): Map<String, String> {
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
            // The picker lists one row per PLANTED TIER (Claude Code 2.1.257: fen()/hen()/uen()
            // emit a row whenever ANTHROPIC_DEFAULT_<tier>_MODEL is set, value = the alias, label =
            // _NAME) and dedupes rows by value only, so two tiers on one model drew that model
            // twice: Sol as opus and as fable on claudex, K2.7 Code as sonnet and as haiku on
            // claude-kimi (the 2026-09-04 release recording). The tier cannot be left unset: the
            // alias then resolves to Claude Code's built-in model, which this head rejects
            // ("proxies its own models only"), and every fable- or haiku-tiered subagent dies.
            // The one lever is the allowlist: a tier row whose model is not on availableModels is
            // hidden, while the head accepts its own DISCOVERY-WRAPPED spelling (catalog.contains
            // unwraps). So a repeated tier is planted as "<prefix><id>": routed like the first, drawn
            // never. Cache rows are untouched on purpose: Claude Code already drops a cache row that
            // repeats a tier's model, and the cache row is where the Default line's label comes from
            // (stripping them printed "currently gpt-5.6-sol[1m]"; verified live 2026-09-04). Known
            // cost: a subagent on the wrapped tier runs under a wrapped ACTIVE id, for which Claude
            // Code does not honor CLAUDE_CODE_MAX_CONTEXT_TOKENS (header note).
            val planted = mutableSetOf<String>()
            slots.forEach { (slot, model) ->
                val repeated = !planted.add(model) && spec.discoveryPrefix.isNotBlank()
                put("ANTHROPIC_DEFAULT_${slot}_MODEL", if (repeated) spec.discoveryPrefix + model else model)
                val label = spec.modelLabels[model] ?: model
                put("ANTHROPIC_DEFAULT_${slot}_MODEL_NAME", label)
                put("ANTHROPIC_DEFAULT_${slot}_MODEL_DESCRIPTION", label)
            }
            // ONE constant client window per process (ModelCatalog.clientLaunchWindow), never the
            // pinned row's number: the row's real window is applied by usage scaling on every turn,
            // so a TOML window edit + `splice restart` reaches THIS process live (2026-09-05).
            // AUTO_COMPACT_WINDOW rides the same number — it is an absolute cap in the client's
            // (scaled) units, and any smaller value would compact early by exactly that ratio.
            put("CLAUDE_CODE_MAX_CONTEXT_TOKENS", spec.contextWindow.toString())
            put("CLAUDE_CODE_AUTO_COMPACT_WINDOW", max(AUTO_COMPACT_FLOOR, spec.contextWindow).toString())
            put("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE", "85")
            put("MAX_THINKING_TOKENS", "128000")
            // Claude Code's default request timeout is 600s and it also bounds the first-byte
            // deadline; the head's whole-turn cap is the wall that decides a turn, so the client
            // must outlive it (spec.apiTimeoutMs = cap + grace). Without this every compaction
            // longer than ten minutes died as client_abort while the daemon was still serving it.
            // Planted unconditionally: the head's own wall owns this deadline, so an ambient
            // API_TIMEOUT_MS is replaced rather than merged (unlike NO_PROXY below); a larger value
            // buys nothing past totalCap and a smaller one recreates the abort.
            // Claude Code's non-streaming fallback (after a streaming error it re-sends the turn
            // with stream:false under this same deadline) stays ENABLED on purpose: the collect
            // path answers it silently until the terminal body, and with the deadline past totalCap
            // the head's wall speaks first. CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK would only
            // delete a recovery path.
            put("API_TIMEOUT_MS", spec.apiTimeoutMs.toString())
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
