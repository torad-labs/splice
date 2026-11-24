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
package splice.app.desktop.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.desktop.record.DesktopPickerRow
import splice.app.provider.ServedProvider
import splice.client.desktop.DoorPickerRow
import splice.client.models.AnthropicTiers
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.ModelTierSuffix
import splice.core.topology.desktop.DesktopConfig
import splice.core.usage.QuotaFullWindow

/** One catalog model as the door serves it now: its provider's facts ([fact]), the provider a turn on it runs on
 *  ([provider]), that provider's rows of it in the picker, one per window ([rows]), and the Claude model it is
 *  ([claude], null for another lab's). */
internal data class ServedModel(
    val fact: CatalogModel,
    val provider: ServedProvider,
    val rows: List<DoorModel>,
    val claude: String?,
    /** The big Code-tab window of splice.toml's `[desktop]`, which [supports1m] is read against: the window a
     *  1M variant promises the model can fill. Claude Code's own million where no desktop table speaks. */
    val millionWindow: Long = CLAUDE_CODE_ONE_MILLION,
) {
    /** The id the gateway lists it under: Claude's own id for Claude's models, else the provider's selector. */
    val gatewayId: String get() = claude ?: provider.catalog.wrap(fact.id)

    /** The selector Desktop receives for this model. */
    val desktopId: String get() = claude ?: provider.catalog.names.nameOf(fact.id)

    /** Whether it has a 1M variant: a row of it carries the `[1m]` hint, or its provider serves the big window. */
    val supports1m: Boolean get() = rows.any(::millionRow) || fact.contextWindow >= millionWindow

    /** Whether the 1M variant is its only one: every row of it carries the hint. */
    val prefer1m: Boolean get() = rows.all(::millionRow)

    private fun millionRow(row: DoorModel): Boolean = row.upstream.endsWith(ONE_MILLION, ignoreCase = true)
}

internal enum class ModelDiscovery {
    GATEWAY,
    DESKTOP,
    ;

    fun id(model: ServedModel): String = when (this) {
        GATEWAY -> model.gatewayId
        DESKTOP -> model.desktopId
    }
}

internal class DoorCatalog(
    private val lineup: DesktopLineup,
    /** splice.toml's `[desktop]`, whose big window is the bar for a served model's 1M variant; Desktop's
     *  shipped pair for a list no desktop table speaks to (the terminal's own, a launch's). */
    private val codeWindows: DesktopConfig = DesktopConfig(),
) {
    private val tiers = AnthropicTiers()
    private val digits = Regex("\\d+")

    /** Claude's tiers, the one listed first between two models of one version. */
    private val tierOrder = listOf("fable", "opus", "sonnet", "haiku")

    /** Each catalog model a provider in the door serves: Claude's models first, newest version to oldest (a tier's
     *  order, fable, opus, sonnet, haiku, between two of one version), then every other model in the catalog's order.
     *  Until Nov 21, 2026 all of them kept the catalog's order, and Claude's models sat in the middle of each list. */
    val served: List<ServedModel> = lineup.catalog.models.mapNotNull(::served).let { all ->
        val (claude, others) = all.partition { it.claude != null }
        claude.sortedWith(::newestFirst) + others
    }

    /** Each tier's newest Claude model served: the one that tier's alias runs. */
    private val newest: Map<String, String> = served.mapNotNull { it.claude }.distinct()
        .groupBy { tiers.tierOf(it) }
        .mapNotNull { (tier, ids) -> tier?.let { it to ids.maxWith(::newer) } }
        .toMap()
    private val wire = DoorModelWire(tiers, newest)

    /** The door's list, as any caller but Claude Desktop discovers it: one per id Desktop asks by, so a Claude model two
     *  providers serve is listed once, on the first. */
    private val door: List<ServedModel> = served.distinctBy { it.desktopId }

    /** Claude Desktop's list: the door's, then the models of the providers only Desktop runs (desktop-antigravity),
     *  which are in this list alone. */
    private val desktop: List<ServedModel> = (door + desktopOnly).distinctBy { it.desktopId }

    /** Both paths use the same model-list format, with each caller's selector for each model. */
    fun discovery(
        first: String? = null,
        surface: ModelDiscovery = ModelDiscovery.GATEWAY,
    ): JsonObject = buildJsonObject {
        val rows = ordered(first, if (surface == ModelDiscovery.DESKTOP) desktop else door)
        put("data", JsonArray(rows.map { wire.discovered(it, surface.id(it)) }))
        put("has_more", false)
        put("first_id", rows.firstOrNull()?.let(surface::id))
        put("last_id", rows.lastOrNull()?.let(surface::id))
    }

    /** Desktop's list as [discovery] gives it, each row's id and name: what its picker holds after this discovery. */
    fun desktopRead(first: String? = null): List<DesktopPickerRow> =
        ordered(first).map { DesktopPickerRow(it.desktopId, it.fact.label) }

    /** Desktop's model settings are a projection of this same gateway catalog. Desktop reads effort controls from
     *  these settings while discovery supplies descriptions; the names and order must match discovery exactly. */
    fun inferenceModels(first: String?): JsonArray = DesktopModelSettings(tiers, newest).models(ordered(first))

    /** [list] (Desktop's, unless a caller names another): Claude's models first, newest to oldest, then the model whose rows hold [first] (Desktop's own
     *  default, `splice desktop on --model`) when it is another lab's, then the rest in their order. Desktop opens a
     *  new session on the first model it lists, so with a Claude model in the door that is the newest one. */
    private fun ordered(first: String?, list: List<ServedModel> = desktop): List<ServedModel> {
        val lead = first?.let { picked -> list.firstOrNull { model -> model.rows.any { it.model == picked } } }
            ?.takeIf { it.claude == null }
            ?: return list
        val (claude, others) = list.partition { it.claude != null }
        return claude + lead + others.filterNot { it === lead }
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

    /** Each catalog model of a provider only Desktop runs, with that provider's rows of it. None is Claude's: those
     *  providers list no Claude id (AntigravityCatalog). */
    private val desktopOnly: List<ServedModel> get() = lineup.catalog.models.mapNotNull { fact ->
        val id = ModelTierSuffix.strip(fact.id)
        val rows = lineup.desktopModels
            .filter { it.provider == fact.provider && ModelTierSuffix.strip(it.upstream) == id }
        lineup.desktopOnly.firstOrNull { it.key == fact.provider }
            ?.takeIf { rows.isNotEmpty() }
            ?.let { ServedModel(fact, it, rows, null, codeWindows.codeWindow1m) }
    }

    private fun served(fact: CatalogModel): ServedModel? {
        val id = ModelTierSuffix.strip(fact.id)
        val picker = lineup.picker.filter { ModelTierSuffix.strip(it.upstream) == id }
        val provider = lineup.providers.firstOrNull { provider ->
            provider.key == fact.provider && picker.any { it.provider == provider.key }
        } ?: return null
        val rows = picker.filter { it.provider == provider.key }
        return ServedModel(fact, provider, rows, tiers.modelOf(id), codeWindows.codeWindow1m)
    }

    /** [row]'s name: its provider's name for the model, with the window its hint names when the model has a row
     *  without one too, and its account's full window beside it. */
    private fun labelOf(model: ServedModel, row: DoorModel): String {
        val hint = row.upstream.removePrefix(ModelTierSuffix.strip(row.upstream))
        val window = if (hint.isEmpty() || model.rows.size == 1) "" else " (${hint.trim('[', ']').uppercase()})"
        val full = row.full ?: return model.fact.label + window
        val mark = if (full.window == QuotaFullWindow.FIVE_HOUR) FIVE_HOUR_MARK else SEVEN_DAY_MARK
        return "${model.fact.label}$window · $mark 100%"
    }

    /** Two of Claude's models, the newer version first; of one version, the higher tier first. */
    private fun newestFirst(a: ServedModel, b: ServedModel): Int {
        val (x, y) = a.claude.orEmpty() to b.claude.orEmpty()
        return newer(y, x).takeIf { it != 0 } ?: (tierRank(x) - tierRank(y))
    }

    private fun tierRank(claude: String): Int =
        tierOrder.indexOf(tiers.tierOf(claude)).let { if (it < 0) tierOrder.size else it }

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

// The windows a picker row names as fully used, as `splice status` names them on its accounts lines.
private const val FIVE_HOUR_MARK = "5h"
private const val SEVEN_DAY_MARK = "7d"
