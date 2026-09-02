// PORT-OF: server/statusline/claudex-statusline.mjs @ pre-public-port-baseline — renders Claude Code's per-tick
// statusline from the JSON blob it pipes on stdin. Claude Code's shape: a top-level
// `context_window` object holding `context_window_size`, `used_percentage`, and a nested
// `current_usage.{input_tokens, cache_read_input_tokens, cache_creation_input_tokens}`
// (`total_input_tokens` is the pre-2.1.132 fallback). Segments: model dot + name, context
// used/window · pct (colored by proximity to compaction), cache-hit %, the soft-warn glyph, and
// the repo · branch. A parse failure falls back to a bare dim marker (never crashes the bar).
package splice.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.model.ModelCatalog
import splice.core.usage.RateLimitState
import splice.core.usage.UsageWarnPolicy
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import java.util.concurrent.TimeUnit

public class StatuslineRenderer(
    private val label: String,
    extraGitRoots: List<String> = emptyList(),
    /** Clock seam: the branch-cache TTL test was a wall-clock race (two real git round-trips inside
     *  a 2s window flake on a loaded runner) — injected time makes expiry deterministic (DR-22c). */
    private val now: WallClock = WallClock(System::currentTimeMillis),
    /** Branch-lookup seam (DR-22 redo): the real git subprocess in production; a test injects a
     *  latched lookup so the concurrent late-publish race is deterministic instead of timing-dependent. */
    branchLookup: GitBranchReader? = null,
    /** The head's catalog when the route knows it. Claude Code fixes its context window per
     *  PROCESS (the pinned row's, via CLAUDE_CODE_MAX_CONTEXT_TOKENS) and splice scales the token
     *  counts it reports so any other row compacts at its own declared window, which leaves the
     *  blob Claude Code pipes back here in client units: on a 500k row over a 256k session the bar
     *  read "…/256k" with counts x 0.512 however the operator switched. The catalog undoes that
     *  scaling for the picked row and names it by its label. Null renders the blob as sent. */
    private val catalog: ModelCatalog? = null,
) {
    // Resolved in the body (not a ctor default) so the real lookup can reference the member gitBranch.
    private val branchLookup: GitBranchReader = branchLookup ?: GitBranchReader { cwd -> gitBranch(cwd) }

    // Operator-trusted roots beyond $HOME//tmp for the git-branch lookup (statuslineGitRoots
    // knob / CLAUDEX_STATUSLINE_GIT_ROOTS) — devcontainer /workspace, /srv layouts. Normalized once.
    private val extraGitRoots: List<java.nio.file.Path> = extraGitRoots.mapNotNull { root ->
        runCatching { java.nio.file.Paths.get(root).toAbsolutePath().normalize() }.getOrNull()
    }

    // Real (symlink-resolved) trusted roots for safeGitCwd's containment check — resolved ONCE here
    // since the root set ($HOME, /tmp, extraGitRoots) is process-invariant, unlike the per-request
    // candidate cwd (still resolved fresh on each call). A root missing at construction is dropped,
    // same as the old per-call runCatching { root.toRealPath() }.getOrNull().
    private val trustedRoots: List<java.nio.file.Path> = (
        listOfNotNull(System.getProperty("user.home"), "/tmp").map { java.nio.file.Paths.get(it) } +
            this.extraGitRoots
        ).mapNotNull { root -> runCatching { root.toRealPath() }.getOrNull() }

    private val json = Json { ignoreUnknownKeys = true }

    private val blob = StatuslineJson()
    private val row = StatuslineRow(catalog)
    private val branchCacheLock = Any()
    private val branchCache = LinkedHashMap<String, CachedBranch>(
        GIT_CACHE_INITIAL_CAPACITY,
        GIT_CACHE_LOAD_FACTOR,
        true,
    )

    public fun render(stdinJson: String, usage: HeadUsageSource?, warnPct: Int, warnTokens5h: Long): String {
        val root = runCatching { json.parseToJsonElement(stdinJson).jsonObject }.getOrNull() ?: return dim(label)
        val segments = listOfNotNull(
            modelSegment(root),
            contextSegment(root),
            cacheSegment(root),
            warnSegment(usage, warnPct, warnTokens5h),
            locationSegment(root),
        )
        return if (segments.isEmpty()) dim(label) else segments.joinToString(SEPARATOR)
    }

    private fun modelSegment(root: JsonObject): String? {
        val model = blob.obj(root, "model") ?: return null
        val id = blob.str(model["id"])
        val name = row.label(id) ?: blob.str(model["display_name"]) ?: id ?: return null
        return "$BOLD$CYAN●$RESET $BOLD$name$RESET"
    }

    private fun contextSegment(root: JsonObject): String? {
        val cw = blob.obj(root, "context_window") ?: return null
        val id = blob.str(blob.obj(root, "model")?.get("id"))
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

    private fun cacheSegment(root: JsonObject): String? {
        val cu = blob.obj(blob.obj(root, "context_window"), "current_usage") ?: return null
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

    private fun warnSegment(usage: HeadUsageSource?, warnPct: Int, warnTokens5h: Long): String? {
        val source = usage ?: return null
        val snapshot = source.snapshot()
        val ratelimit = snapshot.ratelimit?.let {
            RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens)
        }
        val warn = UsageWarnPolicy.computeUsageWarn(snapshot.outputTokens5h, ratelimit, warnPct, warnTokens5h)
        return when (warn.level) {
            "critical" -> "$RED⚠ ${warn.pct}%$RESET"
            "warn" -> "$YELLOW⚠ ${warn.pct}%$RESET"
            else -> null
        }
    }

    private fun locationSegment(root: JsonObject): String? {
        val cwd = blob.str(blob.obj(root, "workspace")?.get("current_dir"))
            ?: blob.str(root["cwd"])
            ?: return null
        val base = cwd.trim('/').substringAfterLast('/').ifEmpty { return null }
        // Only git when cwd RESOLVES to a real directory under the user home (or /tmp) — never
        // exec git -C against an attacker-chosen path from unauthenticated /statusline. Run git in
        // the symlink-resolved path, not the raw cwd.
        val safe = safeGitCwd(cwd)
        val branch = if (safe != null) cachedGitBranch(safe.toString()) else ""
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

    /** The symlink-RESOLVED absolute directory if it lies under $HOME, /tmp, or an operator-trusted
     *  root — else null (the resolved path is what git -C runs in). `normalize()` only collapses
     *  "..": a symlink under /tmp pointing OUTSIDE the trusted roots would pass a lexical prefix
     *  check yet run git elsewhere, so resolve REAL paths on BOTH sides and compare those
     *  (review 2026-07-23). Repos outside the trusted roots lose only the branch segment. */
    internal fun safeGitCwd(cwd: String): java.nio.file.Path? {
        if (!cwd.startsWith("/") || cwd.any { it.code == 0 }) return null
        // toRealPath resolves symlinks AND requires existence — a non-existent path returns null.
        val real = runCatching { java.nio.file.Paths.get(cwd).toRealPath() }.getOrNull() ?: return null
        return real.takeIf { p -> java.nio.file.Files.isDirectory(p) && trustedRoots.any { p.startsWith(it) } }
    }

    private fun cachedGitBranch(cwd: String): String {
        synchronized(branchCacheLock) {
            val cached = branchCache[cwd]
            if (cached != null && now() < cached.expiresAtMs) return cached.branch
        }
        // The subprocess runs OUTSIDE the monitor (DR-22b): the renderer is process-shared per head
        // now, and holding the lock across a 200ms waitFor serialized every concurrent tick behind
        // one blocking git on a Ktor dispatcher thread. Concurrent misses may each run one
        // duplicate git. Stamp the observation BEFORE the lookup so expiry encodes WHEN the branch
        // was read, not when we win the publish lock (DR-22 redo): a slow lookup that publishes late
        // must not look fresher than a racer that read the branch later.
        val observedAt = now()
        val branch = branchLookup(cwd)
        synchronized(branchCacheLock) {
            val expiresAt = observedAt + GIT_CACHE_TTL_MS
            // Revalidate under the lock: a concurrent lookup that observed at-or-after us may already
            // have published a fresher branch. Our older read must not clobber it — keep and return
            // the fresher entry (the unconditional publish here let a slow git overwrite a newer one).
            val existing = branchCache[cwd]
            if (existing != null && existing.expiresAtMs >= expiresAt) return existing.branch
            branchCache[cwd] = CachedBranch(branch, expiresAt)
            while (branchCache.size > GIT_CACHE_MAX_ENTRIES) {
                val iterator = branchCache.keys.iterator()
                iterator.next().run { iterator.remove() }
            }
        }
        return branch
    }

    private fun gitBranch(cwd: String): String = runCatching {
        val process = ProcessBuilder("git", "-C", cwd, "branch", "--show-current")
            .redirectErrorStream(false)
            .start()
        if (!process.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return ""
        }
        process.inputStream.readBytes().decodeToString().trim()
    }.getOrDefault("")

    private fun fmtK(n: Long): String = if (n >= K) "${n / K}k" else n.toString()

    private fun dim(s: String) = "$DIM$s$RESET"
}

private data class CachedBranch(val branch: String, val expiresAtMs: Long)

/** Reads the current git branch for a resolved working directory — the real git subprocess in
 *  production, a latched stand-in in the late-publish race test (DR-22 redo). Named for the ROLE,
 *  not the shape (kt-no-lambda-seam); `operator fun invoke` keeps call sites byte-identical. */
public fun interface GitBranchReader {
    public operator fun invoke(cwd: String): String
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

    fun num(element: JsonElement?): Long? = (element as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
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
private const val GIT_TIMEOUT_MS = 200L
private const val GIT_CACHE_TTL_MS = 2_000L
private const val GIT_CACHE_INITIAL_CAPACITY = 16
private const val GIT_CACHE_LOAD_FACTOR = 0.75f
private const val GIT_CACHE_MAX_ENTRIES = 64
