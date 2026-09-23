// NEW: 2026-09-22 — what a PROVIDER publishes about the models it serves, and how that answer
// compares to the rows splice.toml declares for it.
//
// WHY THIS EXISTS. Every picker row on every head is hand-authored TOML, and until now nothing ever
// compared it to the endpoint. That hides three different failures behind one silence:
//   * a model the vendor shipped is invisible until a human reads the release notes — grok-4.7 was
//     served by api.x.ai for twenty days before anyone noticed it was missing from the picker;
//   * a row whose declared window is ABOVE the model's real ceiling turns graceful compaction into a
//     hard upstream rejection (the splice.toml xai banner warns about exactly this in prose, and
//     prose is not a check);
//   * a row the vendor retired keeps its place in the picker and 400s every turn chosen on it.
// All three are one question — "what does this backend actually serve?" — and every remote provider
// configured here answers it (measured 2026-09-22): the openai-chat dialect at `{base}/models`, the
// anthropic-passthrough dialect at `{base}/v1/models`.
//
// Parsing and comparison stay pure. ModelsHttp owns the socket boundary and supplies the body.
//
// THE COMPARISON IS NOT A COPY. splice's rows carry decisions the endpoint cannot supply and must
// not overwrite: catalog ORDER is the slot assignment, a `[1m]`/`[500k]` tier row is a splice
// spelling that exists upstream only as its bare id, and a window BELOW the real ceiling is a
// deliberate cap (grok-4.3 is declared 256k against a 1M ceiling). So a declared window under the
// published one is reported as CAPPED — a fact, not a fault — and only a window OVER it is red.
//
// AND THE DAEMON NOW ACTS ON THE ANSWER (later on 2026-09-22). The operator: "make sure that splice
// probes the head endpoint for available models instead of having to hardcode them on the toml
// file". At start the daemon asks the same URL (splice.models.discovery.ModelDiscovery) and every
// NEW model below joins the picker with no row — still never overwriting a declared one, for the
// reasons above. EXCLUDED is what stays out, and says why.
package splice.models.list

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.model.ModelEntry
import splice.core.model.ModelTierSuffix
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.ModelDiscoveryConfig
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText

/** One model as the PROVIDER describes it. Every field but [id] is optional because the endpoints
 *  disagree on what they publish: xAI gives a window and aliases, Moonshot a window and a display
 *  name, DeepSeek an id and nothing else. An absent window is "not published", never zero. */
internal data class UpstreamModel(
    public val id: String,
    public val label: String = "",
    public val contextWindow: Long? = null,
    public val aliases: List<String> = emptyList(),
    /** Why the ENDPOINT says this model cannot serve a Claude Code turn, or null when it says
     *  nothing against it. Only an affirmative statement counts (see [UpstreamRosterParser]): a row
     *  that publishes no capabilities at all is not presumed unusable. */
    public val unusable: String? = null,
) {
    /** The id and every spelling the endpoint says resolves to it. */
    public val spellings: List<String> get() = listOf(id) + aliases
}

/** What asking a provider for its model list yielded. Three outcomes, never collapsed into two: a
 *  dialect that publishes no list and an endpoint that refused to serve one have different fixes. */
internal sealed class UpstreamRoster {
    /** The endpoint answered with a list. May legitimately be empty. */
    data class Published(public val models: List<UpstreamModel>) : UpstreamRoster()

    /** This provider shape has no list to ask for; [reason] says why, in the operator's terms. */
    data class Unpublished(public val reason: String) : UpstreamRoster()

    /** There is a list, and it could not be read: unreachable, refused, or not a model list. */
    data class Unreadable(public val detail: String) : UpstreamRoster()
}

/** Where a provider publishes its model list. The dialect decides the default; an operator whose
 *  vendor puts it somewhere else says so with `models_url`, which is why there is no per-vendor
 *  table here — a hardcoded vendor list is the thing this whole file exists to retire. */
internal object UpstreamRosterUrl {

    /** The Codex backend lists `GET {base}/models?client_version=<v>` and answers with the models
     *  whose `minimal_client_version` is at or below <v> — measured 2026-09-22 against
     *  chatgpt.com/backend-api/codex: no version is HTTP 400, `0.1.0` an empty list, `0.200.0` and
     *  above all nine models. The version is a claim about the CLIENT, and here splice is the client:
     *  it speaks the Responses wire itself rather than running codex-rs, so it claims every model the
     *  account may use, which is what every other dialect's list already returns. Pinning a codex-rs
     *  release instead would hide each new model until someone bumped it — the invisible-model
     *  failure discovery exists to end. A model splice cannot drive is excluded the way it is on any
     *  provider, with `discovery`. */
    public const val CODEX_LIST_CLIENT_VERSION: String = "999.0.0"

    /** The list URL for this provider: [override] when one is configured, else its dialect's own.
     *  [authKind] matters for one shape — `chatgpt-oauth` is the Codex backend, whose list takes the
     *  client version above; an api-key Responses provider is the OpenAI API, which lists at
     *  `{base}/models` like every OpenAI-compatible endpoint. */
    public fun of(dialect: Dialect, baseUrl: String, override: String?, authKind: String = ""): String {
        override?.takeIf { it.isNotBlank() }?.let { return it }
        val base = baseUrl.trimEnd('/')
        return when (dialect) {
            Dialect.OPENAI_CHAT -> "$base/models"
            Dialect.ANTHROPIC_PASSTHROUGH -> "$base/v1/models"
            Dialect.OPENAI_RESPONSES ->
                if (authKind == AuthKind.ChatgptOAuth.wire) {
                    "$base/models?client_version=$CODEX_LIST_CLIENT_VERSION"
                } else {
                    "$base/models"
                }
        }
    }

    /** The list URL [provider] is asked at — one derivation for the probe and the cache it fills. */
    public fun of(provider: ProviderConfig): String =
        of(provider.dialect, provider.baseUrl, provider.modelsUrl, provider.auth.kind)
}

/** A model-list body to [UpstreamRoster]. Accepts the two envelopes in the wild — `{"data": [...]}`
 *  (OpenAI's shape, which Moonshot and DeepSeek also serve) and `{"models": [...]}` — and reads each
 *  row through an alias chain, because `id`/`slug`, `display_name`/`name` and
 *  `context_length`/`context_window`/`max_context_length` are all the same field under four vendors. */
internal class UpstreamRosterParser(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /** [url] appears only in the failure sentence, so an operator can see WHICH endpoint misbehaved. */
    public fun parse(body: String, url: String): UpstreamRoster =
        // The failure is not collapsed: it is rendered into the Unreadable detail below, which is the
        // operator-facing sentence this whole branch exists to produce.
        Cancellables.runCatchingCancellable { rows(json.parseToJsonElement(body).jsonObject) }.fold(
            onSuccess = { rows ->
                rows?.let { UpstreamRoster.Published(it.map(::model)) }
                    ?: UpstreamRoster.Unreadable("$url answered without a model list")
            },
            onFailure = { UpstreamRoster.Unreadable("$url did not answer with JSON (${SafeFailureText.render(it)})") },
        )

    /** The rows array under either envelope key, or null when the body carries neither. */
    private fun rows(root: JsonObject): List<JsonObject>? =
        (root["data"] ?: root["models"]).let { it as? JsonArray }?.map { it.jsonObject }

    private fun model(row: JsonObject): UpstreamModel = UpstreamModel(
        id = JsonScalars.str(row, "id") ?: JsonScalars.str(row, "slug").orEmpty(),
        label = JsonScalars.str(row, "display_name") ?: JsonScalars.str(row, "name").orEmpty(),
        contextWindow = JsonScalars.firstLong(row, "context_length", "context_window", "max_context_length"),
        aliases = strings(row["aliases"]),
        unusable = unusable(row),
    )

    /** What the row itself says against serving a Claude Code turn, in the three forms endpoints use
     *  (all measured 2026-09-22). Absence of a field is never a verdict: xAI, Moonshot and DeepSeek
     *  publish no capabilities at all, and presuming them unusable would empty their pickers.
     *   - `visibility = "hide"` — the Codex backend's own "not for a picker" (gpt-reserve,
     *     codex-auto-review); codex-rs hides the same rows.
     *   - output modalities without "text", top level (xAI's language-models list) or under
     *     `architecture` (OpenRouter) — an image or audio model has nothing to say in a transcript.
     *   - `supported_parameters` without "tools" (OpenRouter) — every Claude Code turn carries tools,
     *     and a model that takes none refuses the request. */
    private fun unusable(row: JsonObject): String? {
        val outputs = strings(row["output_modalities"] ?: (row["architecture"] as? JsonObject)?.get("output_modalities"))
        val parameters = row["supported_parameters"] as? JsonArray
        return when {
            JsonScalars.str(row, "visibility") == "hide" -> "the endpoint hides it from pickers"
            outputs.isNotEmpty() && "text" !in outputs -> "it produces no text (${outputs.joinToString("+")})"
            parameters != null && "tools" !in strings(parameters) -> "it takes no tools, and every Claude Code turn sends them"
            else -> null
        }
    }

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { JsonScalars.str(it) }
}

/** How one declared row stands against what the provider publishes. */
internal enum class RosterVerdict {
    /** The endpoint serves this id and agrees with (or says nothing about) its declared window. */
    SERVED,

    /** The endpoint serves it with a LARGER window than this row declares — a deliberate cap. */
    CAPPED,

    /** The declared window is ABOVE the published ceiling: the compactor will overrun it. */
    OVER_CEILING,

    /** The endpoint does not list this id under any spelling: every turn chosen on it is refused. */
    UNSERVED,

    /** The endpoint serves a model no row declares; the daemon discovers it into the picker. */
    NEW,

    /** The endpoint serves a model no row declares, and it stays out of the picker: the endpoint
     *  says it cannot run a turn, the provider's `discovery` filter excludes it, or the provider is a
     *  local runtime, whose list names a file rather than a model. */
    EXCLUDED,
}

/** One row of the comparison. [declaredWindow] is null for a NEW model, [upstreamWindow] is null
 *  whenever the endpoint publishes no window for it. */
internal data class RosterRow(
    public val id: String,
    public val verdict: RosterVerdict,
    public val declaredWindow: Long? = null,
    public val upstreamWindow: Long? = null,
    public val note: String = "",
)

/** splice.toml's rows against the endpoint's, in both directions.
 *
 *  BOTH DIRECTIONS IS THE POINT. A comparison that only walked the declared rows could never report
 *  the model the vendor shipped this morning, which is the question that starts every one of these
 *  investigations; one that only walked the endpoint could never report the retired row that 400s. */
internal class RosterDiff {

    /** [local] is the provider's own `isLocal`, and it changes ONE verdict: a user-managed runtime
     *  publishes the file it loaded (llama-server lists the .gguf path) and serves THAT model
     *  whatever id the request names — measured 2026-09-22, a turn on the declared `bonsai-2-27b`
     *  was answered by the listed gguf. So an unlisted id is a naming difference there, not the
     *  refused turn it would be on a remote provider, and reporting it as a fault would train the
     *  operator to ignore the one verdict that means "this row cannot work". */
    public fun of(
        declared: List<ModelEntry>,
        published: List<UpstreamModel>,
        local: Boolean = false,
        discovery: ModelDiscoveryConfig = ModelDiscoveryConfig(),
    ): List<RosterRow> {
        val bySpelling = published.flatMap { model -> model.spellings.map { it to model } }.toMap()
        val declaredRows = declared.map { entry -> row(entry, bySpelling[ModelTierSuffix.strip(entry.id)], local) }
        val claimed = declared.mapTo(HashSet()) { ModelTierSuffix.strip(it.id) }
        val newRows = published
            .filterNot { model -> model.spellings.any { it in claimed } }
            .map { model -> undeclared(model, local, discovery) }
        return declaredRows + newRows
    }

    /** A served model no row declares: discovered into the picker, or kept out and why — the same
     *  three reasons, in the same order, the daemon's discovery applies (ModelDiscovery). */
    private fun undeclared(model: UpstreamModel, local: Boolean, discovery: ModelDiscoveryConfig): RosterRow {
        val keptOut = when {
            local -> "a local runtime lists the file it loaded — declare a row to name it"
            model.unusable != null -> "kept out of the picker: ${model.unusable}"
            !discovery.admits(model.id) -> "kept out of the picker by this provider's discovery filter"
            else -> null
        }
        return RosterRow(
            id = model.id,
            verdict = if (keptOut == null) RosterVerdict.NEW else RosterVerdict.EXCLUDED,
            upstreamWindow = model.contextWindow,
            note = keptOut ?: "discovered: in the picker with no row",
        )
    }

    /** Split in two on the one question that decides everything below it — did the endpoint list
     *  this row at all — because a single function carrying both halves sat exactly at detekt's
     *  cyclomatic ceiling (2026-09-22). The verdicts are unchanged; only the seam is new. */
    private fun row(entry: ModelEntry, upstream: UpstreamModel?, local: Boolean): RosterRow =
        if (upstream == null) unmatched(entry, local) else matched(entry, upstream)

    /** The endpoint lists nothing under this id, under any spelling. On a remote provider that is
     *  the refused turn; on a local runtime it is a naming difference (see [of]). */
    private fun unmatched(entry: ModelEntry, local: Boolean): RosterRow {
        val bare = ModelTierSuffix.strip(entry.id)
        val note = if (local) {
            "the runtime lists no '$bare' — it serves the model it loaded whatever id is sent"
        } else {
            "the endpoint lists no model '$bare' — turns chosen on this row are refused upstream"
        }
        return RosterRow(
            id = entry.id,
            verdict = if (local) RosterVerdict.SERVED else RosterVerdict.UNSERVED,
            declaredWindow = entry.contextWindow,
            note = note,
        )
    }

    /** The endpoint serves this row, so the verdict is entirely about the WINDOW. */
    private fun matched(entry: ModelEntry, upstream: UpstreamModel): RosterRow {
        val ceiling = upstream.contextWindow
            ?: return RosterRow(
                id = entry.id,
                verdict = RosterVerdict.SERVED,
                declaredWindow = entry.contextWindow,
                note = "served; the endpoint publishes no window, so this row's number is unchecked",
            )
        val aliased = aliasNote(entry.id, upstream)
        return when {
            entry.contextWindow > ceiling -> RosterRow(
                id = entry.id,
                verdict = RosterVerdict.OVER_CEILING,
                declaredWindow = entry.contextWindow,
                upstreamWindow = ceiling,
                note = "the endpoint serves $ceiling$aliased — compaction runs past what it accepts",
            )
            entry.contextWindow < ceiling -> RosterRow(
                id = entry.id,
                verdict = RosterVerdict.CAPPED,
                declaredWindow = entry.contextWindow,
                upstreamWindow = ceiling,
                note = "the endpoint serves $ceiling$aliased — this row caps it",
            )
            else -> RosterRow(
                id = entry.id,
                verdict = RosterVerdict.SERVED,
                declaredWindow = entry.contextWindow,
                upstreamWindow = ceiling,
                note = if (aliased.isEmpty()) "" else "served$aliased",
            )
        }
    }

    /** " (→ <upstream id>)" when the declared row reached this model through an ALIAS, else "" —
     *  which spelling answered is the difference between a row an operator can verify at the vendor
     *  and one they cannot find there at all. */
    private fun aliasNote(declared: String, upstream: UpstreamModel): String =
        if (upstream.id == ModelTierSuffix.strip(declared)) "" else " (→ ${upstream.id})"
}
