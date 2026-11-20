// NEW: 2026-11-20 — the door's models as Claude Desktop's gateway discovery reads them: `GET /door/v1/models`, which a
// Desktop pointed at the door asks for its picker (and gives Claude Code its model list from). Each row is one model of
// the door's picker under the id Claude's clients keep (ReadableNames: `claude-xai--4.7` for `claude-grok--grok-4.7`,
// which they would drop), with the fields Desktop reads beside it: `display_name`, the Claude tier it stands for
// (`anthropic_family_tier`, `is_family_default`), its 1M variant (`supports_1m`, `prefer_1m`), its highest effort
// (`max_effort_level`), the Claude model whose effort levels and limits it takes (`behaves_as`) and its window
// (`max_input_tokens`). Rows of one model with and without a `[1m]` hint are one row with its 1M variant.
package splice.app.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.models.AnthropicTiers
import splice.core.model.ModelTierSuffix
import splice.core.util.JsonScalars

internal class DoorModels {
    private val tiers = AnthropicTiers()

    /** The tiers Desktop reads in `anthropic_family_tier`. */
    private val desktopTiers = setOf("opus", "sonnet", "haiku", "fable", "mythos")

    /** [lineup]'s picker as Desktop's `/v1/models` answer, every row on one page. */
    fun json(lineup: DesktopLineup): JsonObject {
        val rows = rows(lineup)
        val ids = rows.map { JsonScalars.str(it, "id") }
        return buildJsonObject {
            put("data", JsonArray(rows))
            put("has_more", false)
            put("first_id", ids.firstOrNull())
            put("last_id", ids.lastOrNull())
        }
    }

    private fun rows(lineup: DesktopLineup): List<JsonObject> {
        val grouped = lineup.picker.groupBy { it.head to ModelTierSuffix.strip(it.upstream) }
        val claudeOfTier = LinkedHashMap<String, String>()
        grouped.keys.mapNotNull { (_, model) -> tiers.modelOf(model) }.forEach { claude ->
            tiers.tierOf(claude)?.let { claudeOfTier.putIfAbsent(it, claude) }
        }
        val firstOfTier = HashSet<String>()
        return grouped.mapNotNull { (key, models) ->
            lineup.heads.firstOrNull { it.key == key.first }?.let { head ->
                row(head, key.second, models, claudeOfTier, firstOfTier)
            }
        }
    }

    /** One model of [head] as a row, from its rows in the picker ([variants]). */
    private fun row(
        head: DoorHead,
        model: String,
        variants: List<DoorModel>,
        claudeOfTier: Map<String, String>,
        firstOfTier: MutableSet<String>,
    ): JsonObject {
        val claude = tiers.modelOf(model)
        val tier = tierOf(head, model, claude)
        // The first of Claude's own models for a tier is the one the tier's alias runs.
        val familyDefault = claude?.let { tier }?.let(firstOfTier::add) == true
        return buildJsonObject {
            put("type", "model")
            put("id", head.catalog.names.nameOf(model))
            put("display_name", variants.first().label.ifEmpty { model })
            tier?.let { put("anthropic_family_tier", it) }
            if (familyDefault) put("is_family_default", true)
            windows(this, head, variants, claude == null)
            put("max_effort_level", if (claude != null) CLAUDE_EFFORT else OTHER_EFFORT)
            (claude ?: tier?.let(claudeOfTier::get))?.let { put("behaves_as", it) }
        }
    }

    /** The Claude tier [model] stands for: its own, for Claude's, else the tier its head names it for. */
    private fun tierOf(head: DoorHead, model: String, claude: String?): String? =
        claude?.let(tiers::tierOf)
            ?: head.tiers.entries.firstOrNull { ModelTierSuffix.strip(it.value) == model }?.key
                ?.takeIf { it in desktopTiers }

    /** A model's window and its 1M variant: offered when a row of it carries `[1m]` or holds a million tokens, and the
     *  only one shown when every row does, save Claude's own, whose window without the hint is its smaller one. */
    private fun windows(into: JsonObjectBuilder, head: DoorHead, variants: List<DoorModel>, other: Boolean) {
        val windows = variants.map { head.catalog.contextWindowFor(it.upstream) }
        val tagged = variants.map { it.upstream.endsWith(ONE_MILLION, ignoreCase = true) }
        val million = variants.indices.map { tagged[it] || windows[it] >= ONE_MILLION_TOKENS }
        into.put("supports_1m", million.any { it })
        val onlyMillion = if (other) million.all { it } else tagged.all { it }
        if (onlyMillion) into.put("prefer_1m", true)
        into.put("max_input_tokens", windows.max())
    }
}

private const val ONE_MILLION = "[1m]"
private const val ONE_MILLION_TOKENS = 1_000_000L

/** Claude's own models take every effort level Desktop offers; another lab's model, the highest but one, which splice
 *  maps onto what that model's dialect takes. */
private const val CLAUDE_EFFORT = "max"
private const val OTHER_EFFORT = "xhigh"
