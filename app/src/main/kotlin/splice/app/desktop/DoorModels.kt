// NEW: 2026-11-20 — the door's models as Claude Desktop's gateway discovery reads them: `GET /door/v1/models`, which a
// Desktop pointed at the door asks for its picker (and gives Claude Code its model list from). Each row is one model of
// the door's picker under the id Claude's clients keep (ReadableNames: `claude-xai--4.7` for `claude-grok--grok-4.7`,
// which they would drop), with the fields Desktop reads beside it: `display_name`, the Claude tier it stands for
// (`anthropic_family_tier`, `is_family_default`), its 1M variant (`supports_1m`, `prefer_1m`), its highest effort
// (`max_effort_level`), the Claude model whose effort levels and limits it takes (`behaves_as`: its tier's newest
// Claude model, Opus's for a model with no tier) and its window (`max_input_tokens`). Rows of one model with and
// without a `[1m]` hint are one row with its 1M variant.
package splice.app.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.models.AnthropicTiers
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.ModelTierSuffix
import splice.core.util.JsonScalars

internal class DoorModels {
    private val tiers = AnthropicTiers()

    /** The tiers Desktop reads in `anthropic_family_tier`. */
    private val desktopTiers = setOf(OPUS, "sonnet", "haiku", "fable", "mythos")
    private val digits = Regex("\\d+")

    /** [lineup]'s picker as Desktop's `/v1/models` answer, every row on one page. */
    fun json(lineup: DesktopLineup): JsonObject {
        // One row per id: two heads serving one Claude model list it once, on the first.
        val rows = rows(lineup).distinctBy { JsonScalars.str(it, "id") }
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
        // Each tier's newest Claude model in the door: the one its alias runs, and the one another lab's model of that
        // tier behaves as.
        val claudeOfTier = grouped.keys.mapNotNull { (_, model) -> tiers.modelOf(model) }.distinct()
            .groupBy { tiers.tierOf(it) }
            .mapNotNull { (tier, ids) -> tier?.let { it to ids.maxWith(::newer) } }
            .toMap()
        return grouped.mapNotNull { (key, models) ->
            lineup.heads.firstOrNull { it.key == key.first }?.let { head ->
                row(head, key.second, models, claudeOfTier)
            }
        }
    }

    /** One model of [head] as a row, from its rows in the picker ([variants]). */
    private fun row(
        head: DoorHead,
        model: String,
        variants: List<DoorModel>,
        claudeOfTier: Map<String, String>,
    ): JsonObject {
        val claude = tiers.modelOf(model)
        val tier = tierOf(head, model, claude)
        val familyDefault = claude != null && claude == tier?.let(claudeOfTier::get)
        return buildJsonObject {
            put("type", "model")
            // Claude's own models go under their own ids: Desktop finds a model's effort levels by its id alone
            // (its catalog and the levels it ships, keyed `claude-opus-5-5`), and reads no other field for it from
            // this list, so `claude--claude-opus-5-5` had none. The door runs a named Claude id on the head that serves
            // it (DoorRouting.served).
            put("id", claude ?: head.catalog.names.nameOf(model))
            put("display_name", variants.first().label.ifEmpty { model })
            tier?.let { put("anthropic_family_tier", it) }
            if (familyDefault) put("is_family_default", true)
            windows(this, head, variants, claude == null)
            put("max_effort_level", if (claude != null) CLAUDE_EFFORT else OTHER_EFFORT)
            (claude ?: claudeOfTier[tier ?: OPUS])?.let { put("behaves_as", it) }
        }
    }

    /** Which of two of Claude's models of one tier is newer, by the version numbers in their ids (`claude-opus-5-5`
     *  after `claude-opus-5`, `claude-opus-4-8`). */
    private fun newer(a: String, b: String): Int {
        val (x, y) = version(a) to version(b)
        return x.zip(y).firstOrNull { (p, q) -> p != q }?.let { (p, q) -> p.compareTo(q) } ?: x.size.compareTo(y.size)
    }

    private fun version(claude: String): List<Int> = digits.findAll(claude).map { it.value.toInt() }.toList()

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
        val million = variants.indices.map { tagged[it] || windows[it] >= CLAUDE_CODE_ONE_MILLION }
        into.put("supports_1m", million.any { it })
        val onlyMillion = if (other) million.all { it } else tagged.all { it }
        if (onlyMillion) into.put("prefer_1m", true)
        into.put("max_input_tokens", windows.max())
    }
}

private const val ONE_MILLION = "[1m]"

/** The tier another lab's model with none of its own behaves as: Claude's most capable. */
private const val OPUS = "opus"

/** Claude's own models take every effort level Desktop offers; another lab's model, the highest but one, which splice
 *  maps onto what that model's dialect takes. */
private const val CLAUDE_EFFORT = "max"
private const val OTHER_EFFORT = "xhigh"
