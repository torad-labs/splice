// NEW: 2026-11-20 — every list of models the door gives, read from the one model catalog published last
// (PublishedCatalog) and the door as it stands: Claude Desktop's gateway discovery (`GET /door/v1/models`), which fills
// its picker in Chat, Cowork and the Code tab, the terminal's picker and another machine's (the same rows), and the
// models `splice desktop` and `splice claude` name. What a model is (its name, description, reasoning levels, window)
// is its provider's, from the catalog; which provider runs it, under which id a client asks for it and whether its
// account is spent are the door's. A catalog model no provider in the door serves now is in none of them.
//
// Until Nov 20, 2026 these lists were built from the running heads at each call (DoorModels, LineupJson.models), with
// effort from two constants: every other lab's model said `xhigh` and every Claude model `max`, whatever its provider
// lists, and each lab's model took a Claude tier from a head's `model_slots`.
package splice.app.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.provider.ServedProvider
import splice.client.desktop.DoorPickerRow
import splice.client.models.AnthropicTiers
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.ModelTierSuffix
import splice.core.usage.QuotaFullWindow

/** One catalog model as the door serves it now: its provider's facts ([fact]), the provider a turn on it runs on
 *  ([provider]), that provider's rows of it in the picker, one per window ([rows]), and the Claude model it is
 *  ([claude], null for another lab's). */
internal data class ServedModel(
    val fact: CatalogModel,
    val provider: ServedProvider,
    val rows: List<DoorModel>,
    val claude: String?,
) {
    /** The id Claude Desktop asks for it by: Claude's own id for Claude's models, whose effort levels Desktop finds by
     *  that id alone, else the provider's name for it that Claude's clients keep (ReadableNames). */
    val desktopId: String get() = claude ?: provider.catalog.names.nameOf(fact.id)

    /** Whether it has a 1M variant: a row of it carries the `[1m]` hint, or its provider serves a million tokens. */
    val supports1m: Boolean get() = rows.any(::millionRow) || fact.contextWindow >= CLAUDE_CODE_ONE_MILLION

    /** Whether the 1M variant is its only one: every row of it carries the hint. */
    val prefer1m: Boolean get() = rows.all(::millionRow)

    private fun millionRow(row: DoorModel): Boolean = row.upstream.endsWith(ONE_MILLION, ignoreCase = true)
}

internal class DoorCatalog(private val lineup: DesktopLineup) {
    private val tiers = AnthropicTiers()
    private val digits = Regex("\\d+")

    /** The levels of Claude Desktop's Effort control, lowest first. */
    private val desktopEfforts = listOf("low", "medium", "high", "xhigh", "max")

    /** Each catalog model a provider in the door serves, in the catalog's order. */
    val served: List<ServedModel> = lineup.catalog.models.mapNotNull(::served)

    /** Each tier's newest Claude model served: the one that tier's alias runs. */
    private val newest: Map<String, String> = served.mapNotNull { it.claude }.distinct()
        .groupBy { tiers.tierOf(it) }
        .mapNotNull { (tier, ids) -> tier?.let { it to ids.maxWith(::newer) } }
        .toMap()

    /** Claude Desktop's list: one per id it asks by, so a Claude model two providers serve is listed once, on the
     *  first. */
    private val desktop: List<ServedModel> = served.distinctBy { it.desktopId }

    /** Gateway discovery's `/v1/models` answer, every model on one page. */
    fun discovery(first: String? = null): JsonObject = buildJsonObject {
        val rows = ordered(first)
        put("data", JsonArray(rows.map(::discovered)))
        put("has_more", false)
        put("first_id", rows.firstOrNull()?.desktopId)
        put("last_id", rows.lastOrNull()?.desktopId)
    }

    /** Desktop's list as [discovery] gives it, each row's id and name: what its picker holds after this discovery. */
    fun desktopRead(first: String? = null): List<DesktopPickerRow> =
        ordered(first).map { DesktopPickerRow(it.desktopId, it.fact.label) }

    /** Desktop's list with the model whose rows hold [first] (Desktop's own default, `splice desktop on --model`)
     *  first: Desktop opens a new session on the first model it lists. The rest keep their order. */
    private fun ordered(first: String?): List<ServedModel> {
        val lead = first?.let { picked -> desktop.firstOrNull { model -> model.rows.any { it.model == picked } } }
            ?: return desktop
        return listOf(lead) + desktop.filterNot { it === lead }
    }

    /** A terminal session's picker, and another machine's: each served model's rows under its provider's name for it,
     *  a row with a window hint beside one without named for its window too. A model whose account reads a plan window
     *  fully used stays listed, with the window marked beside its name as `splice status` writes it (`7d 100%`, Nov 17,
     *  2026): its provider still serves it, and the person sees before picking it that the account is spent. */
    fun picker(): List<DoorPickerRow> = served.flatMap { model ->
        model.rows.map { row -> DoorPickerRow(row.model, labelOf(model, row), row.provider, model.fact.description) }
    }

    /** Claude Desktop's list as `splice desktop --json` gives it: each model's id there, name and provider. */
    fun desktopJson(): JsonArray = rowsJson(desktop.map { Listed(it.desktopId, it.fact.label, it.provider.key) })

    /** The picker as `splice claude --json` gives it: each row's id, name and provider. */
    fun pickerJson(): JsonArray = rowsJson(
        served.flatMap { model -> model.rows.map { Listed(it.model, labelOf(model, it), model.provider.key) } },
    )

    private fun rowsJson(rows: List<Listed>): JsonArray = buildJsonArray {
        rows.forEach { row ->
            addJsonObject {
                put("model", row.model)
                put("label", row.label)
                put("provider", row.provider)
                // What a head was named and run as, kept for a script that reads them: the provider's key now.
                put("head", row.provider)
                put("command", row.provider)
            }
        }
    }

    private fun served(fact: CatalogModel): ServedModel? {
        val id = ModelTierSuffix.strip(fact.id)
        val picker = lineup.picker.filter { ModelTierSuffix.strip(it.upstream) == id }
        val provider = lineup.providers.firstOrNull { provider ->
            provider.key == fact.provider && picker.any { it.provider == provider.key }
        } ?: return null
        return ServedModel(fact, provider, picker.filter { it.provider == provider.key }, tiers.modelOf(id))
    }

    private fun discovered(model: ServedModel): JsonObject = buildJsonObject {
        put("type", "model")
        put("id", model.desktopId)
        put("display_name", model.fact.label)
        if (model.fact.description.isNotBlank()) put("description", model.fact.description)
        // Only Claude's own models are a Claude tier: Desktop runs a tier, such as a sub-agent that asks for `sonnet`,
        // on the model marked for it.
        model.claude?.let(tiers::tierOf)?.let { tier ->
            put("anthropic_family_tier", tier)
            if (newest[tier] == model.claude) put("is_family_default", true)
        }
        put("supports_1m", model.supports1m)
        if (model.prefer1m) put("prefer_1m", true)
        behavesAs(model)?.let { put("behaves_as", it) }
        maxEffort(model)?.let { put("max_effort_level", it) }
        if (model.fact.contextWindow > 0) put("max_input_tokens", model.fact.contextWindow)
    }

    /** The Claude model whose Effort control another lab's model takes: the newest Opus served, for a model its
     *  provider lists as reasoning. A model its provider says nothing of reasoning about gets none. */
    private fun behavesAs(model: ServedModel): String? =
        newest[OPUS].takeIf { model.claude == null && (model.fact.reasons || model.fact.reasoningLevels.isNotEmpty()) }

    /** The highest of Desktop's Effort levels the provider lists for the model; none when it lists none. */
    fun maxEffort(model: ServedModel): String? = desktopEfforts.lastOrNull { it in model.fact.reasoningLevels }

    /** [row]'s name: its provider's name for the model, with the window its hint names when the model has a row
     *  without one too, and its account's full window beside it. */
    private fun labelOf(model: ServedModel, row: DoorModel): String {
        val hint = row.upstream.removePrefix(ModelTierSuffix.strip(row.upstream))
        val window = if (hint.isEmpty() || model.rows.size == 1) "" else " (${hint.trim('[', ']').uppercase()})"
        val full = row.full ?: return model.fact.label + window
        val mark = if (full.window == QuotaFullWindow.FIVE_HOUR) FIVE_HOUR_MARK else SEVEN_DAY_MARK
        return "${model.fact.label}$window · $mark 100%"
    }

    /** Which of two of Claude's models of one tier is newer, by the version numbers in their ids (`claude-opus-5-5`
     *  after `claude-opus-5`, `claude-opus-4-8`). */
    private fun newer(a: String, b: String): Int {
        val (x, y) = version(a) to version(b)
        return x.zip(y).firstOrNull { (p, q) -> p != q }?.let { (p, q) -> p.compareTo(q) } ?: x.size.compareTo(y.size)
    }

    private fun version(claude: String): List<Int> = digits.findAll(claude).map { it.value.toInt() }.toList()
}

/** A model as `splice desktop` and `splice claude` list it: the id asked by, its name and the provider that runs it. */
private data class Listed(val model: String, val label: String, val provider: String)

/** The window hint Claude Code holds a million-token window for. */
private const val ONE_MILLION = "[1m]"

/** The tier whose newest Claude model lends its Effort control to another lab's reasoning model: the one with every
 *  level. */
private const val OPUS = "opus"

// The windows a picker row names as fully used, as `splice status` names them on its accounts lines.
private const val FIVE_HOUR_MARK = "5h"
private const val SEVEN_DAY_MARK = "7d"
