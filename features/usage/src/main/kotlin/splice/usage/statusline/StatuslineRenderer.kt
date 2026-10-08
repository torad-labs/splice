// PORT-OF: server/statusline/claudex-statusline.mjs @ pre-public-port-baseline — renders Claude Code's per-tick
// statusline from the JSON blob it pipes on stdin. Claude Code's shape: a top-level
// `context_window` object holding `context_window_size`, `used_percentage`, and a nested
// `current_usage.{input_tokens, cache_read_input_tokens, cache_creation_input_tokens}`
// (`total_input_tokens` is the pre-2.1.132 fallback). Segments: model dot + name, context
// used/window · pct (colored by proximity to compaction), cache-hit %, the soft-warn glyph, and
// the repo · branch. A parse failure falls back to a bare dim marker (never crashes the bar).
package splice.usage.statusline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.accounts.pool.HeadAccountPoolSource
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
import splice.core.usage.RateLimitState
import splice.core.usage.UsageWarn
import splice.core.usage.UsageWarnPolicy
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.util.concurrent.TimeUnit

internal class StatuslineRenderer(
    private val label: String,
    /** The repo and branch lookup behind the location segment. */
    private val git: StatuslineGit = StatuslineGit(),
    /** Clock seam for the limit and warn segments and the session start. */
    private val now: WallClock = WallClock(System::currentTimeMillis),
    /** The head's catalog when the route knows it. Claude Code fixes its context window per
     *  PROCESS (the pinned row's, via CLAUDE_CODE_MAX_CONTEXT_TOKENS) and splice scales the token
     *  counts it reports so any other row compacts at its own declared window, which leaves the
     *  blob Claude Code pipes back here in client units: on a 500k row over a 256k session the bar
     *  read "…/256k" with counts x 0.512 however the operator switched. The catalog undoes that
     *  scaling for the picked row and names it by its label. Null renders the blob as sent. */
    private val catalog: ModelCatalog? = null,
    /** Where each post's (session_id, context_window_size) is recorded for an env-governed row, so
     *  the head can scale THAT session's counts against the window it really runs with. */
    private val clientWindows: ClientWindows? = null,
    /** Secret-free live account state; safe on the unauthenticated statusline route. */
    private val accountPool: HeadAccountPoolSource? = null,
    /** What the cost segment reads: the head's session cost, perf skips and upstream (V4-37/45/240). */
    private val spend: StatuslineSpend = StatuslineSpend(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val blob = StatuslineJson()
    private val row = StatuslineRow(catalog)
    private val windowLearner = StatuslineWindowLearner(catalog, clientWindows)

    /** V4-132 (FEATURES.md §4.5 "Claude windows", §6 "statusline rate_limits capture"): each
     *  session's last captured `rate_limits` snapshot. Internal, not wrapped in an accessor fun —
     *  [StatuslineRenderer] already sits at detekt's 15-function class ceiling, and a property read
     *  ([StatuslineRateLimits.forSession]) costs it nothing a wrapper function would. */
    internal val rateLimits = StatuslineRateLimits()
    private val bars = StatuslineBars()

    fun render(
        stdinJson: String,
        usage: HeadUsageSource?,
        warn: StatuslineWarn,
        sessionId: String? = null,
        /** V4-274: this head has not answered the session since it took it over, so a usage the post
         *  carries is another head's last turn (StatuslineUsageOwner). */
        unanswered: Boolean = false,
    ): String {
        // A malformed or absent payload IS answered, on the next line, by the dim label: that is the
        // designed degradation for the one input splice does not author. No sink here, and this runs
        // once per statusline tick.
        // ast-grep-ignore: kt-no-silent-result-collapse -- the failure is answered by the dim-label fallback on the next line
        val root = Cancellables.runCatchingCancellable { json.parseToJsonElement(stdinJson).jsonObject }.getOrNull()
            ?: return dim(label)
        windowLearner.learn(root)
        val snapshot = usage?.snapshot()
        val pool = accountPool?.view(sessionId)
        val account = pool?.selectedAccount()
        rateLimits.record(sessionId, account?.label, root)
        val selectedQuota = pool?.selectedQuota()
        val switchReason = pool?.lastSwitch?.takeIf { it.to == account?.label }?.reason
        val accountText = account?.let { selected ->
            switchReason?.let { "${selected.label} ${dim("← $it")}" } ?: selected.label
        }
        val modelId = blob.str(blob.obj(root, MODEL_FIELD)?.get("id"))
        val nowSeconds = TimeUnit.MILLISECONDS.toSeconds(now())
        val segments = listOfNotNull(
            modelSegment(root),
            accountText,
            spend.segment(bars, root, sessionId, modelId, blob.sessionStartMs(root, now())),
        ) +
            bars.limitSegments(root, selectedQuota ?: snapshot?.quota, selectedQuota != null, nowSeconds) +
            listOfNotNull(
                contextSegment(root, unanswered),
                cacheSegment(root, unanswered),
                warnSegment(snapshot, warn, nowSeconds),
                locationSegment(root),
            )
        return if (segments.isEmpty()) dim(label) else segments.joinToString(SEPARATOR)
    }

    private fun modelSegment(root: JsonObject): String? {
        val model = blob.obj(root, MODEL_FIELD) ?: return null
        val id = blob.str(model["id"])
        val name = row.label(id) ?: blob.str(model["display_name"]) ?: id ?: return null
        val effort = bars.effort(root)?.let { "${dim("·")}$it" }.orEmpty()
        return "$BOLD$CYAN●$RESET $BOLD$name$RESET$effort"
    }

    private fun contextSegment(root: JsonObject, unanswered: Boolean): String? {
        val cw = blob.ownContextWindow(root, unanswered) ?: return null
        val id = blob.str(blob.obj(root, MODEL_FIELD)?.get("id"))
        val (size, used) = row.window(id, blob.num(cw["context_window_size"]) ?: 0, usedTokens(cw))
        val pct = blob.num(cw["used_percentage"])?.toInt() ?: if (size > 0) (used * PERCENT / size).toInt() else 0
        val color = when {
            pct >= CTX_CRITICAL_PCT -> RED
            pct >= CTX_WARN_PCT -> YELLOW
            else -> GREEN
        }
        val window = if (size > 0) "${fmtK(used)}/${fmtK(size)}" else fmtK(used)
        return "$window ${dim("·")} $color$pct%$RESET"
    }

    private fun cacheSegment(root: JsonObject, unanswered: Boolean): String? {
        val cu = blob.obj(blob.ownContextWindow(root, unanswered), "current_usage") ?: return null
        val hit = cacheHitPct(cu) ?: return null
        return "${cacheColor(hit)}⚡ $hit%$RESET"
    }

    private fun cacheHitPct(cu: JsonObject): Int? {
        val read = blob.num(cu["cache_read_input_tokens"]) ?: 0
        val total = (blob.num(cu["input_tokens"]) ?: 0) + read + (blob.num(cu["cache_creation_input_tokens"]) ?: 0)
        return if (total <= 0) null else (read * PERCENT / total).toInt()
    }

    private fun cacheColor(hit: Int): String = when {
        hit >= CACHE_GOOD_PCT -> GREEN
        hit >= CACHE_OK_PCT -> YELLOW
        else -> DIM
    }

    private fun warnSegment(snapshot: UsageView?, warn: StatuslineWarn, nowSeconds: Long): String? {
        snapshot ?: return null
        val level = warn.levelOf(snapshot, nowSeconds)
        return when (level.level) {
            "critical" -> "$RED⚠ ${level.pct}%$RESET"
            "warn" -> "$YELLOW⚠ ${level.pct}%$RESET"
            else -> null
        }
    }

    private fun locationSegment(root: JsonObject): String? {
        val cwd = blob.str(blob.obj(root, "workspace")?.get("current_dir"))
            ?: blob.str(root["cwd"])
            ?: return null
        val base = cwd.trim('/').substringAfterLast('/').ifEmpty { return null }
        // StatuslineGit runs git only when cwd RESOLVES to a real directory under a trusted root —
        // never git -C against an attacker-chosen path from unauthenticated /statusline.
        val branch = git.branchOf(cwd)
        val loc = if (branch.isEmpty()) base else "$base  ⎇ $branch"
        return dim(loc)
    }

    /** current_usage.* is the correct per-turn count on every version; total_input_tokens is the
     * pre-2.1.132 fallback. */
    private fun usedTokens(cw: JsonObject): Long {
        val cu = blob.obj(cw, "current_usage") ?: return blob.num(cw["total_input_tokens"]) ?: 0
        return (blob.num(cu["input_tokens"]) ?: 0) +
            (blob.num(cu["cache_read_input_tokens"]) ?: 0) +
            (blob.num(cu["cache_creation_input_tokens"]) ?: 0)
    }

    private fun fmtK(n: Long): String = if (n >= K) "${n / K}k" else n.toString()

    private fun dim(s: String) = "$DIM$s$RESET"
}

/** [pct] is the soft-warn percentage (0 disables the warn tier, V4-109); [tokens5h] the 5h output cap. */
internal class StatuslineWarn(private val pct: Int, private val tokens5h: Long) {

    /** The warn level of [snapshot] at [nowSeconds]; only a current ratelimit reading counts. */
    fun levelOf(snapshot: UsageView, nowSeconds: Long): UsageWarn {
        val ratelimit = snapshot.ratelimit?.currentAt(nowSeconds)?.let {
            RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens)
        }
        return UsageWarnPolicy.computeUsageWarn(snapshot.outputTokens5h, ratelimit, pct, tokens5h)
    }
}

// The stdin-blob JSON adapter, split out so StatuslineRenderer stays inside detekt's per-class
// function budget: the renderer holds 11 + fmtK + dim = 13 of 15, and folding obj/str/num back in
// makes 16.
private class StatuslineJson {
    fun obj(parent: JsonObject?, key: String): JsonObject? = parent?.get(key) as? JsonObject

    // Through JsonScalars, not a second `as? JsonPrimitive` read: JsonNull IS a JsonPrimitive whose
    // content is the literal "null", which is non-empty, so the unfiltered read survived takeIf and
    // rendered the word "null" instead of falling through to model.id / root.cwd (review 2026-08-28,
    // PR 99). The same class this PR fixes in SystemTextSerializer and ContentSerializer.
    fun str(element: JsonElement?): String? = JsonScalars.str(element)?.takeIf { it.isNotEmpty() }

    fun num(element: JsonElement?): Long? = JsonScalars.str(element)?.toDoubleOrNull()?.toLong()

    /** The post's `context_window`, or null when [unanswered] and it carries a usage: that usage is
     *  another head's last turn, neither this head's context nor its cache hit (V4-274). A post with
     *  no usage yet still reads zero, which is no other head's figure. */
    fun ownContextWindow(root: JsonObject, unanswered: Boolean): JsonObject? =
        obj(root, "context_window")?.takeUnless { unanswered && obj(it, "current_usage") != null }

    /** When the client session began: its `cost.total_duration_ms` before [nowMs], or null when the
     *  blob does not carry it (V4-240 review, finding 4c). */
    fun sessionStartMs(root: JsonObject, nowMs: Long): Long? =
        num(obj(root, "cost")?.get("total_duration_ms"))?.let { nowMs - it }
}

// StatuslineRenderer's companion constants at their sanctioned file-scope home. The ANSI values
// carry raw ESC bytes and were moved verbatim — only the modifier and the indentation changed.
private const val RESET = "[0m"
private const val DIM = "[2m"
private const val BOLD = "[1m"
private const val CYAN = "[36m"
private const val GREEN = "[32m"
private const val YELLOW = "[33m"
private const val RED = "[31m"
private const val SEPARATOR = "[2m   [0m"
private const val PERCENT = 100
private const val CTX_CRITICAL_PCT = 85
private const val CTX_WARN_PCT = 60
private const val CACHE_GOOD_PCT = 70
private const val CACHE_OK_PCT = 40
private const val K = 1000

// V4-132: pulled out of 4 call sites (StringLiteralDuplication, threshold 4) once
// StatuslineRateLimits.kt's modelScoped() added a fourth read of the same JSON field name.
// internal, not private: StatuslineRateLimits.kt (same package) reaches this one constant across
// the concentration split rather than carrying a second "model" literal of its own.
internal const val MODEL_FIELD = "model"
