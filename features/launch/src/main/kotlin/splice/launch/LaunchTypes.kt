// NEW: the launch request bag and the exec recipe it produces. Split from
// LaunchService.kt so the assembler is not billed for the DTOs
// (concentration, 2026-08-19). LAYOUT-01: the launch feature's shared read model — the recipe, the
// Claude head's wrap and the resume hook all read a head's spec.
package splice.launch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.ClaudePolicy
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.CLIENT_TABLE_WINDOW
import splice.core.model.ClientSpelling
import splice.core.model.ModelCatalog
import splice.core.util.JsonScalars
import java.nio.file.Path

private const val PICKER_VALUE = "value"
private const val PICKER_LABEL = "label"

/** The transcript trees one launch may look at (V4-115): the head's OWN CLAUDE_CONFIG_DIR and every
 *  OTHER head's. They are ONE fact — which trees this head can adopt a named session out of — so they
 *  travel as one value, which also keeps [LaunchSpec] inside the constructor-width ratchet
 *  (ConstructorWidthLawTest) instead of widening it one field at a time. */
public data class HeadTrees(
    val own: Path,
    val siblings: List<Path> = emptyList(),
)

/** How a head's models map onto Claude Code's own model names: its tier slots
 *  ("opus"/"sonnet"/"haiku"/"fable") and, for a presented row, the Claude model it is resolved as. One
 *  value, because every part answers the same question and [LaunchSpec] sits at the constructor-width
 *  ratchet. */
public data class ModelTiers(
    /** id -> tier slot, declared per row in the head's catalog. Empty = fall back to
     *  [splice.launch.recipe.LaunchService]'s positional heuristic, which is what every catalog used before slots existed
     *  and is the reason splice.toml carries an "ORDER IS LOAD-BEARING" banner. Non-empty = ONLY the
     *  declared tiers are emitted — positional order is fully retired for that head, and an
     *  undeclared tier stays un-set rather than pointing a second alias at an already-claimed model
     *  (the 2-model duplication this exists to remove). */
    val slots: Map<String, String> = emptyMap(),
    /** The ids the positional heuristic may place, in catalog order (ModelCatalog.tierModelIds), or
     *  null for every offered id. 2026-09-22: a model the endpoint lists but no row declares joins
     *  the picker and never a slot — slot order is a decision splice.toml makes, and a vendor's list
     *  order is not one (OpenRouter's would put an arbitrary model behind `opus`). */
    val candidates: List<String>? = null,
    /** V4-232: settings.json `modelOverrides` (ModelCatalog.presented): each Claude model a presented row
     *  is resolved as -> the row's id. Empty for a head that presents none. */
    val modelOverrides: Map<String, String> = emptyMap(),
    /** V4-358: row id -> the id the CLIENT is handed for it ([ClientSpelling]: the 1M hint), for the rows
     *  that differ. Every id decision here (tier names, slots, labels, the resume rewrite) stays on the
     *  row's own id; only what the client is handed is spelled, at the last step ([LaunchSpec.heldByClient],
     *  [clientId]). Empty for a head that spells none, which is every head under 425k and every
     *  client-auth one. */
    val spelled: Map<String, String> = emptyMap(),
) {
    /** V4-232: the client's window for the presented rows, [CLIENT_TABLE_WINDOW], or 0 when there are
     *  none. The client compacts at min(window, CLAUDE_CODE_AUTO_COMPACT_WINDOW), so the launch plants
     *  that env no lower (LaunchService.buildEnv), or a presented row on a small runtime would compact
     *  at a fraction of its window. */
    val presentedWindow: Long get() = if (modelOverrides.isEmpty()) 0L else CLIENT_TABLE_WINDOW

    /** V4-358: the client's window for the spelled rows, 1e6, or 0 when there are none: the same cap on
     *  the auto-compact env as [presentedWindow], for the same reason. Without it a row spelled 1M on an
     *  872k head would compact at min(1e6, 872k) reported tokens, 13% early once scaled. */
    val spelledWindow: Long get() = if (spelled.isEmpty()) 0L else CLAUDE_CODE_ONE_MILLION

    /** The id the client is handed for row [id]. */
    public fun clientId(id: String): String = spelled[id] ?: id
}

/** What a head needs to produce a launch recipe (supplied by :app at wiring time). */
public data class LaunchSpec(
    val trees: HeadTrees,
    val models: LaunchModels,
    val signIn: LaunchSignIn,
    val gateway: LaunchGateway,
    val policy: ClaudePolicy,
) {
    /** This boot-assembled spec with the current catalog's roster, labels, picker and windows.
     *  Read per launch: provider discoveries and accepted TOML window edits need no daemon restart.
     *  Discovered rows join the picker, never the positional tiers; client spellings follow their windows. */
    public fun withWindows(catalog: ModelCatalog): LaunchSpec {
        val current = catalog.live()
        val refreshed = copy(
            models = models.copy(
                availableModelIds = current.availableModelIds(),
                modelLabels = current.models.associate {
                    it.id to it.label.ifBlank { models.modelLabels[it.id] ?: it.id }
                },
                contextWindow = current.clientLaunchWindow,
                modelOptionsCache = pickerRows(current),
                tiers = models.tiers.copy(
                    candidates = models.tiers.candidates?.let { current.tierModelIds() },
                    modelOverrides = current.presented.overrides,
                ),
            ),
        )
        val tiers = refreshed.models.tiers.copy(spelled = refreshed.spelledIn(current))
        return refreshed.copy(models = refreshed.models.copy(tiers = tiers))
    }

    /** V4-358: this spec as the client is handed it: the pinned row, the allowlist and each picker row
     *  under [ModelTiers.clientId]. The allowlist keeps the row's own id BESIDE its spelled one, so a
     *  session written before the row was spelled (its transcript names the bare id) still resumes on
     *  it instead of being retagged onto the pinned model, which drops the thinking of every row it
     *  moves. The picker is drawn from the tier slots and the options cache, never from the allowlist,
     *  so the extra id adds no row. This very spec when nothing is spelled. */
    public fun heldByClient(): LaunchSpec {
        val tiers = models.tiers
        if (tiers.spelled.isEmpty()) return this
        val options = (models.modelOptionsCache as? JsonArray)?.let { rows -> JsonArray(rows.map(::heldRow)) }
        return copy(
            models = models.copy(
                pinnedModel = tiers.clientId(models.pinnedModel),
                availableModelIds = models.availableModelIds.flatMap { listOfNotNull(tiers.spelled[it], it) },
                modelOptionsCache = options ?: models.modelOptionsCache,
            ),
        )
    }

    private fun spelledIn(catalog: ModelCatalog): Map<String, String> {
        if (gateway.forwardClientAuth) return emptyMap()
        val spelling = ClientSpelling(catalog)
        return (listOf(models.pinnedModel) + models.availableModelIds).distinct()
            .associateWith(spelling::of)
            .filter { (id, held) -> held != id }
    }

    private fun heldRow(row: JsonElement): JsonElement {
        val option = row as? JsonObject ?: return row
        val value = JsonScalars.str(option, PICKER_VALUE) ?: return row
        return JsonObject(option + (PICKER_VALUE to JsonPrimitive(models.tiers.clientId(value))))
    }

    private fun pickerRows(catalog: ModelCatalog): JsonElement {
        val rows = models.modelOptionsCache as? JsonArray ?: return models.modelOptionsCache
        val kept = rows.filterIsInstance<JsonObject>().associateBy { JsonScalars.str(it, PICKER_VALUE) }
        val options = catalog.models.map { model ->
            val old = kept[model.id]
            val labelChanged = model.label != models.modelLabels[model.id]
            val includesLabel = old == null || PICKER_LABEL in old
            val label = model.label.ifBlank { old?.let { JsonScalars.str(it, PICKER_LABEL) } ?: model.id }
            buildJsonObject {
                old?.forEach { (key, value) -> put(key, value) }
                put(PICKER_VALUE, model.id)
                if (includesLabel || labelChanged) put(PICKER_LABEL, label)
                if (old == null) put("description", model.description.ifBlank { model.shownName() })
                put("context_window", model.contextWindow)
            }
        }
        return JsonArray(options + rows.filterNot { it is JsonObject })
    }
}

public data class LaunchRecipe(
    val env: Map<String, String>,
    val unset: List<String>,
    val argv: List<String>,
    // Non-null only when dangerouslySkipPermissions was engaged — surfaced to the operator via the
    // control log and the /launch response so the danger is never silent.
    val warning: String? = null,
)

/** What a launch came to: a recipe to run, or a refusal to say. */
public sealed class LaunchOutcome {
    public data class Ready(val recipe: LaunchRecipe) : LaunchOutcome()

    /** The launch must not run: [reason] says why, in words the operator can act on. */
    public data class Refused(val reason: String) : LaunchOutcome()
}
