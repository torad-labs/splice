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
// PARSING AND COMPARING ARE PURE AND LIVE HERE; THE SOCKET DOES NOT. :core opens no network
// connection (ModuleLawsTest pins that), so the caller performs the GET and hands the body in.
//
// THE COMPARISON IS NOT A COPY. splice's rows carry decisions the endpoint cannot supply and must
// not overwrite: catalog ORDER is the slot assignment, a `[1m]`/`[500k]` tier row is a splice
// spelling that exists upstream only as its bare id, and a window BELOW the real ceiling is a
// deliberate cap (grok-4.3 is declared 256k against a 1M ceiling). So a declared window under the
// published one is reported as CAPPED — a fact, not a fault — and only a window OVER it is red.
package splice.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.topology.Dialect
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText

/** One model as the PROVIDER describes it. Every field but [id] is optional because the endpoints
 *  disagree on what they publish: xAI gives a window and aliases, Moonshot a window and a display
 *  name, DeepSeek an id and nothing else. An absent window is "not published", never zero. */
public data class UpstreamModel(
    public val id: String,
    public val label: String = "",
    public val contextWindow: Long? = null,
    public val aliases: List<String> = emptyList(),
) {
    /** The id and every spelling the endpoint says resolves to it. */
    public val spellings: List<String> get() = listOf(id) + aliases
}

/** What asking a provider for its model list yielded. Three outcomes, never collapsed into two: a
 *  dialect that publishes no list and an endpoint that refused to serve one have different fixes. */
public sealed class UpstreamRoster {
    /** The endpoint answered with a list. May legitimately be empty. */
    public data class Published(public val models: List<UpstreamModel>) : UpstreamRoster()

    /** This provider shape has no list to ask for; [reason] says why, in the operator's terms. */
    public data class Unpublished(public val reason: String) : UpstreamRoster()

    /** There is a list, and it could not be read: unreachable, refused, or not a model list. */
    public data class Unreadable(public val detail: String) : UpstreamRoster()
}

/** The trailing numeric tier hint a picker row may carry — `[1m]`, `[500k]` — declared ONCE.
 *
 *  [ModelCatalog] owns the same grammar for the wire path (what the upstream actually sees); this
 *  object is that grammar, so the roster comparison and the catalog cannot drift into two readings
 *  of what a tier suffix is. Numeric-bracket only (DR-27): a genuine vendor id such as
 *  `model[preview]` is not a tier and rides through untouched. */
public object ModelTierSuffix {
    private val hint = Regex("\\[\\d+[km]]$", RegexOption.IGNORE_CASE)

    /** [id] with a trailing tier hint removed; every other id is returned unchanged. */
    public fun strip(id: String): String = id.replace(hint, "")

    /** True when [id] ends in a tier hint — a splice spelling, not a vendor one. */
    public fun present(id: String): Boolean = hint.containsMatchIn(id)
}

/** Where a provider publishes its model list. The dialect decides the default; an operator whose
 *  vendor puts it somewhere else says so with `models_url`, which is why there is no per-vendor
 *  table here — a hardcoded vendor list is the thing this whole file exists to retire. */
public object UpstreamRosterUrl {

    /** The Codex backend does publish a list, behind a `client_version` query whose accepted values
     *  are the Codex CLI's own releases, and it carries no context windows. Guessing a version is
     *  a moving hardcode; an operator who wants it points `models_url` straight at it. */
    public const val RESPONSES_HAS_NO_LIST: String =
        "the openai-responses dialect publishes no model list splice can ask for without pinning a " +
            "client version — set models_url on the provider to name one"

    /** The list URL for this provider, or null when the dialect has none and none was configured. */
    public fun of(dialect: Dialect, baseUrl: String, override: String?): String? {
        override?.takeIf { it.isNotBlank() }?.let { return it }
        val base = baseUrl.trimEnd('/')
        return when (dialect) {
            Dialect.OPENAI_CHAT -> "$base/models"
            Dialect.ANTHROPIC_PASSTHROUGH -> "$base/v1/models"
            Dialect.OPENAI_RESPONSES -> null
        }
    }
}

/** A model-list body to [UpstreamRoster]. Accepts the two envelopes in the wild — `{"data": [...]}`
 *  (OpenAI's shape, which Moonshot and DeepSeek also serve) and `{"models": [...]}` — and reads each
 *  row through an alias chain, because `id`/`slug`, `display_name`/`name` and
 *  `context_length`/`context_window`/`max_context_length` are all the same field under four vendors. */
public class UpstreamRosterParser(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /** [url] appears only in the failure sentence, so an operator can see WHICH endpoint misbehaved. */
    public fun parse(body: String, url: String): UpstreamRoster =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-22: the failure is NOT collapsed — it
        // is rendered into the Unreadable detail below, which is the operator-facing sentence this
        // whole branch exists to produce.
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
        aliases = (row["aliases"] as? JsonArray).orEmpty().mapNotNull { JsonScalars.str(it) },
    )
}

/** How one declared row stands against what the provider publishes. */
public enum class RosterVerdict {
    /** The endpoint serves this id and agrees with (or says nothing about) its declared window. */
    SERVED,

    /** The endpoint serves it with a LARGER window than this row declares — a deliberate cap. */
    CAPPED,

    /** The declared window is ABOVE the published ceiling: the compactor will overrun it. */
    OVER_CEILING,

    /** The endpoint does not list this id under any spelling: every turn chosen on it is refused. */
    UNSERVED,

    /** The endpoint serves a model no row declares, so it cannot be reached from the picker. */
    NEW,
}

/** One row of the comparison. [declaredWindow] is null for a NEW model, [upstreamWindow] is null
 *  whenever the endpoint publishes no window for it. */
public data class RosterRow(
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
public class RosterDiff {

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
    ): List<RosterRow> {
        val bySpelling = published.flatMap { model -> model.spellings.map { it to model } }.toMap()
        val declaredRows = declared.map { entry -> row(entry, bySpelling[ModelTierSuffix.strip(entry.id)], local) }
        val claimed = declared.mapTo(HashSet()) { ModelTierSuffix.strip(it.id) }
        val newRows = published
            .filterNot { model -> model.spellings.any { it in claimed } }
            .map { model ->
                RosterRow(
                    id = model.id,
                    verdict = RosterVerdict.NEW,
                    upstreamWindow = model.contextWindow,
                    note = "served by the endpoint, declared by no row — add it to reach it from /model",
                )
            }
        return declaredRows + newRows
    }

    private fun row(entry: ModelEntry, upstream: UpstreamModel?, local: Boolean): RosterRow {
        val bare = ModelTierSuffix.strip(entry.id)
        if (upstream == null) {
            return RosterRow(
                id = entry.id,
                verdict = if (local) RosterVerdict.SERVED else RosterVerdict.UNSERVED,
                declaredWindow = entry.contextWindow,
                note = if (local) {
                    "the runtime lists no '$bare' — it serves the model it loaded whatever id is sent"
                } else {
                    "the endpoint lists no model '$bare' — turns chosen on this row are refused upstream"
                },
            )
        }
        val ceiling = upstream.contextWindow
            ?: return RosterRow(
                id = entry.id,
                verdict = RosterVerdict.SERVED,
                declaredWindow = entry.contextWindow,
                note = "served; the endpoint publishes no window, so this row's number is unchecked",
            )
        val aliased = if (upstream.id == bare) "" else " (→ ${upstream.id})"
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
}
