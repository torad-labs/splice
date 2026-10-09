package splice.launch

import kotlinx.serialization.json.JsonElement

/** The model side of a launch: which row is pinned, which rows are offered, how they are labelled and
 *  slotted, and the window planted for the client. One value because every part answers "what may this
 *  head's session run on", and [LaunchSpec] stays inside the constructor-width ratchet. */
public data class LaunchModels(
    val pinnedModel: String,
    val availableModelIds: List<String>,
    val modelLabels: Map<String, String>, // id -> display label (for the alias slot names)
    /** The client window planted as CLAUDE_CODE_MAX_CONTEXT_TOKENS: ModelCatalog.clientLaunchWindow,
     *  a constant. Per-row windows never ride the env — usage scaling applies them on the wire. */
    val contextWindow: Long,
    val modelOptionsCache: JsonElement, // the /model picker option list
    /** Which models may stand behind Claude Code's tier slots — see [ModelTiers]. */
    val tiers: ModelTiers = ModelTiers(),
    /** The head's discovery prefix ("claude-codex--"): a tier that repeats an earlier tier's model
     *  is planted under this wrapped spelling so the picker's allowlist hides its row (see
     *  LaunchService.buildEnv). Blank keeps the duplicate row. */
    val discoveryPrefix: String = "",
)
