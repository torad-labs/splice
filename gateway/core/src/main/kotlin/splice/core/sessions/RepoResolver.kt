// NEW: V4-130, FEATURES.md 4.4 and 6 — the git repository a session's cwd belongs to, so the console
// can group the whole registry by project in one read.
//
// FROM THE FILESYSTEM, NOT FROM `git rev-parse`. :core spawns no process (ModuleLawsTest's OS-escape
// arm), and a route that forked git once per registry row on every poll would be the wrong cost
// anyway. The answer rev-parse gives is a pure function of files git itself reads, so it is read here
// the same way:
//   - a `.git` DIRECTORY at a level means that level is a main checkout: it is the repo;
//   - a `.git` FILE holds `gitdir: <path>`; a linked worktree's gitdir carries a `commondir` file
//     naming the shared git dir, and the repo is that common dir's parent (`--git-common-dir`
//     folded back to a checkout), with the worktree kept as the sub-label;
//   - a gitdir with no `commondir` is a submodule or a separated git dir: its level is its own repo.
// The walk stops at the trusted root that contains the cwd, so a `.git` above the trusted set is
// never read.
//
// TRUSTED ROOTS ONLY — the set the statusline's git-branch probe uses (StatuslineRenderer.safeGitCwd:
// $HOME, /tmp and the statuslineGitRoots knob), realpath-resolved on BOTH sides so a symlink under a
// trusted root cannot lead the walk outside it. A cwd the resolver will not or cannot place answers
// with the cwd itself as its root AND the reason, never a blank: the console groups such a session
// under its cwd and prints why.
//
// Cached with the statusline branch cache's TTL and bound (StatuslineRenderer GIT_CACHE_TTL_MS,
// GIT_CACHE_MAX_ENTRIES), per the row: the board polls the registry, and a checkout's layout does not
// move between two polls.
package splice.core.sessions

import splice.core.util.Cancellables
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

private const val REPO_CACHE_TTL_MS = 2_000L
private const val REPO_CACHE_MAX_ENTRIES = 64
private const val CACHE_LOAD_FACTOR = 0.75f

/** A pointer file is a single short line; anything larger is not a git pointer and is not read. */
private const val MAX_POINTER_BYTES = 4_096L

public const val REASON_NOT_ABSOLUTE: String = "the cwd is not an absolute path"
public const val REASON_MISSING: String = "the cwd does not exist on this host"
public const val REASON_UNTRUSTED: String =
    "the cwd is outside the trusted roots (\$HOME, /tmp, statuslineGitRoots), so it was not probed"
public const val REASON_NO_REPO: String = "the cwd is not inside a git repository"

/** Where a session groups. [root] is always set: the shared repo when one was found, else the cwd
 *  itself with [reason] saying why. [worktree] is set only for a linked worktree of [root]. */
public data class RepoRoot(val root: String, val worktree: String? = null, val reason: String? = null)

private data class CachedRoot(val root: RepoRoot, val expiresAtMs: Long)

public class RepoResolver(
    extraRoots: List<String> = emptyList(),
    home: String? = System.getProperty("user.home"),
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) {
    /** Resolved once: the root set is process-invariant. A root that does not exist on this host
     *  (a devcontainer /workspace) is simply not a trusted root. */
    private val trustedRoots: List<Path> = (listOfNotNull(home, "/tmp") + extraRoots).mapNotNull(::realPath)

    private val cache = LinkedHashMap<String, CachedRoot>(REPO_CACHE_MAX_ENTRIES, CACHE_LOAD_FACTOR, true)

    public fun resolve(cwd: String): RepoRoot {
        val now = clock()
        synchronized(cache) { cache[cwd]?.takeIf { now < it.expiresAtMs }?.let { return it.root } }
        val root = place(cwd)
        synchronized(cache) {
            cache[cwd] = CachedRoot(root, now + REPO_CACHE_TTL_MS)
            while (cache.size > REPO_CACHE_MAX_ENTRIES) cache.remove(cache.keys.first())
        }
        return root
    }

    private fun place(cwd: String): RepoRoot {
        val real = directory(cwd)
            ?: return RepoRoot(cwd, reason = if (absolute(cwd)) REASON_MISSING else REASON_NOT_ABSOLUTE)
        val boundary = trustedRoots.filter { real.startsWith(it) }.maxByOrNull { it.nameCount }
            ?: return RepoRoot(cwd, reason = REASON_UNTRUSTED)
        return walkUp(real, boundary) ?: RepoRoot(cwd, reason = REASON_NO_REPO)
    }

    /** The innermost repo from [real] up to and including [boundary], never above it. */
    private fun walkUp(real: Path, boundary: Path): RepoRoot? =
        generateSequence(real) { it.parent }
            .takeWhile { it.startsWith(boundary) }
            .firstNotNullOfOrNull(::repoAt)

    /** The repo [level] is the top of, when it has a `.git` entry git itself would honour. */
    private fun repoAt(level: Path): RepoRoot? {
        val dotGit = level.resolve(".git")
        return when {
            Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS) -> RepoRoot(level.toString())
            Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS) -> linked(level, dotGit)
            else -> null
        }
    }

    private fun linked(level: Path, dotGit: Path): RepoRoot? {
        val gitDir = pointer(dotGit, "gitdir:")?.let { level.resolve(it).normalize() } ?: return null
        val common = pointer(gitDir.resolve("commondir"), "")?.let { gitDir.resolve(it).normalize() }
        val repo = common?.let { if (it.fileName?.toString() == ".git") it.parent else it }
        return if (repo == null || repo == level) {
            RepoRoot(level.toString())
        } else {
            RepoRoot(repo.toString(), worktree = level.toString())
        }
    }

    /** The first line of a git pointer file with [prefix] stripped, or null when it is not one. */
    private fun pointer(file: Path, prefix: String): String? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- an unreadable or absent pointer file means "not this layout", which is the answer the caller branches on
        Cancellables.runCatchingCancellable {
            if (Files.size(file) > MAX_POINTER_BYTES) null else Files.readAllLines(file).firstOrNull()
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun absolute(cwd: String): Boolean = cwd.startsWith("/") && cwd.none { it.code == 0 }

    private fun directory(cwd: String): Path? =
        if (absolute(cwd)) realPath(cwd)?.takeIf { Files.isDirectory(it) } else null

    private fun realPath(raw: String): Path? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- an unresolvable path is REASON_MISSING (or not a trusted root) by definition, not a swallowed failure
        Cancellables.runCatchingCancellable { Paths.get(raw).toRealPath() }.getOrNull()
}
