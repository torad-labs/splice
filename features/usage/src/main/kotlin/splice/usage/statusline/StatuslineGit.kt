// NEW: the statusline's repo/branch lookup, moved out of StatuslineRenderer so the renderer's
// constructor stops carrying four git-only collaborators (extra roots, home, clock, branch reader)
// and the unauthenticated /statusline route's one security-relevant rule — git runs only under a
// trusted root — lives in one place with its own tests.
package splice.usage.statusline

import splice.core.util.Cancellables
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Resolves the git branch for a client-supplied working directory, only where that directory is
 * under the [home] (or /tmp, or an operator-trusted extra root).
 *
 * [now] is the branch-cache TTL clock (DR-22c): the TTL test was a wall-clock race, and an injected
 * clock makes expiry deterministic. [lookup] is the branch-reader seam (DR-22 redo): the real git
 * subprocess in production, a latched stand-in for the concurrent late-publish race test.
 */
internal class StatuslineGit(
    extraRoots: List<String> = emptyList(),
    home: Path? = null,
    private val now: WallClock = WallClock(System::currentTimeMillis),
    lookup: GitBranchReader? = null,
) {
    // Resolved in the body (not a ctor default) so the real lookup can reference the member gitBranch.
    private val lookup: GitBranchReader = lookup ?: GitBranchReader { cwd -> gitBranch(cwd) }

    // Operator-trusted roots beyond the home and /tmp for the git-branch lookup (statuslineGitRoots
    // knob / CLAUDEX_STATUSLINE_GIT_ROOTS) — devcontainer /workspace, /srv layouts. Normalized once.
    private val extraRoots: List<Path> = extraRoots.mapNotNull { root ->
        // A configured root that is not a usable path is simply not a trusted root. The statusline
        // has no sink and Claude Code renders on every tick, so a line per render would be noise on
        // the hottest cosmetic path in splice.
        // ast-grep-ignore: kt-no-silent-result-collapse -- an unusable configured root is not a failure, it is just not a trusted root
        Cancellables.runCatchingCancellable { Paths.get(root).toAbsolutePath().normalize() }.getOrNull()
    }

    // Real (symlink-resolved) trusted roots for safeCwd's containment check — resolved ONCE here
    // since the root set (the home, /tmp, extraRoots) is process-invariant, unlike the per-request
    // candidate cwd (still resolved fresh on each call). A root missing at construction is dropped.
    // An ABSENT trusted root is not a failure to report: /workspace and /srv do not exist on most
    // hosts, and the containment check treats "unresolvable" and "not under a trusted root" as the
    // same answer.
    private val trustedRoots: List<Path> = (
        listOfNotNull(home, Paths.get("/tmp")) +
            this.extraRoots
        // ast-grep-ignore: kt-no-silent-result-collapse -- an absent optional root proves absence, not failure
        ).mapNotNull { root -> Cancellables.runCatchingCancellable { root.toRealPath() }.getOrNull() }

    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, CachedBranch>(GIT_CACHE_INITIAL_CAPACITY, GIT_CACHE_LOAD_FACTOR, true)

    /** The branch of [cwd], or empty when [cwd] is not under a trusted root or has none. */
    fun branchOf(cwd: String): String = safeCwd(cwd)?.let { cachedBranch(it.toString()) }.orEmpty()

    /** The symlink-RESOLVED absolute directory if it lies under $HOME, /tmp, or an operator-trusted
     *  root — else null (the resolved path is what git -C runs in). `normalize()` only collapses
     *  "..": a symlink under /tmp pointing OUTSIDE the trusted roots would pass a lexical prefix
     *  check yet run git elsewhere, so resolve REAL paths on BOTH sides and compare those
     *  (review 2026-07-23). Repos outside the trusted roots lose only the branch segment. */
    fun safeCwd(cwd: String): Path? {
        if (!cwd.startsWith("/") || cwd.any { it.code == 0 }) return null
        // toRealPath resolves symlinks AND requires existence — a non-existent path returns null.
        // null IS this function's answer for an untrusted cwd: "could not be resolved" and "outside
        // every trusted root" are deliberately the same outcome, both meaning the git probe must
        // not run.
        // ast-grep-ignore: kt-no-silent-result-collapse -- null is this function's ANSWER for an untrusted cwd, not a swallowed failure
        val real = Cancellables.runCatchingCancellable { Paths.get(cwd).toRealPath() }.getOrNull()
            ?: return null
        return real.takeIf { p -> Files.isDirectory(p) && trustedRoots.any { p.startsWith(it) } }
    }

    private fun cachedBranch(cwd: String): String {
        synchronized(cacheLock) {
            val cached = cache[cwd]
            if (cached != null && now() < cached.expiresAtMs) return cached.branch
        }
        // The subprocess runs OUTSIDE the monitor (DR-22b): the renderer is process-shared per head
        // now, and holding the lock across a 200ms waitFor serialized every concurrent tick behind
        // one blocking git on a Ktor dispatcher thread. Concurrent misses may each run one
        // duplicate git. Stamp the observation BEFORE the lookup so expiry encodes WHEN the branch
        // was read, not when we win the publish lock (DR-22 redo): a slow lookup that publishes late
        // must not look fresher than a racer that read the branch later.
        val observedAt = now()
        val branch = lookup(cwd)
        synchronized(cacheLock) {
            val expiresAt = observedAt + GIT_CACHE_TTL_MS
            // Revalidate under the lock: a concurrent lookup that observed at-or-after us may already
            // have published a fresher branch. Our older read must not clobber it — keep and return
            // the fresher entry (the unconditional publish here let a slow git overwrite a newer one).
            val existing = cache[cwd]
            if (existing != null && existing.expiresAtMs >= expiresAt) return existing.branch
            cache[cwd] = CachedBranch(branch, expiresAt)
            while (cache.size > GIT_CACHE_MAX_ENTRIES) {
                val iterator = cache.keys.iterator()
                iterator.next().run { iterator.remove() }
            }
        }
        return branch
    }

    // Any git failure means no branch segment, which is the designed empty-string fallback: a
    // statusline must not fail because a repository is odd, and git is not installed at all on
    // some hosts.
    // ast-grep-ignore: kt-no-silent-result-collapse -- every git failure means the same designed outcome: no branch segment
    private fun gitBranch(cwd: String): String = Cancellables.runCatchingCancellable {
        val process = ProcessBuilder("git", "-C", cwd, "branch", "--show-current")
            .redirectErrorStream(false)
            .start()
        if (!process.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return ""
        }
        process.inputStream.readBytes().decodeToString().trim()
    }.getOrDefault("")
}

private data class CachedBranch(val branch: String, val expiresAtMs: Long)

/** Reads the current git branch for a resolved working directory — the real git subprocess in
 *  production, a latched stand-in in the late-publish race test (DR-22 redo). Named for the ROLE,
 *  not the shape (kt-no-lambda-seam); `operator fun invoke` keeps call sites byte-identical. */
internal fun interface GitBranchReader {
    operator fun invoke(cwd: String): String
}

// why: a hung git must not hold a statusline tick; 200 ms is the longest the bar waits before it drops the branch
private const val GIT_TIMEOUT_MS = 200L

// why: Claude Code ticks the bar several times a second; two seconds of cache keeps a branch switch visible without a git per tick
private const val GIT_CACHE_TTL_MS = 2_000L

// why: LinkedHashMap's own defaults, spelled so the third argument (access order, for LRU eviction) can be passed
private const val GIT_CACHE_INITIAL_CAPACITY = 16

// why: LinkedHashMap's own default load factor, same reason as the capacity
private const val GIT_CACHE_LOAD_FACTOR = 0.75f

// why: one entry per working directory seen; 64 covers a busy operator's sessions and bounds the map
private const val GIT_CACHE_MAX_ENTRIES = 64
