// NEW: cross-head resume through ONE shared transcript tree (V4-64, 2026-09-16; re-landed by V4-168,
// 2026-09-19). Claude Code keeps every transcript under $CLAUDE_CONFIG_DIR/projects/<encoded-cwd>/
// <sessionId>.jsonl (plus a <sessionId>/ subdir of subagent transcripts and tool results), and
// `--resume` finds a session by listing exactly that tree. Each head launches with its own
// CLAUDE_CONFIG_DIR, so a session started on claudex is invisible to claude-kimi's resume picker —
// splice keeps no server-side state a resume needs, so filesystem visibility IS the feature. Linking
// every head's projects/ at the operator's global ~/.claude/projects makes one session resumable from
// any head when an account runs dry (the operator's words at V4-64).
//
// THE HISTORY THIS FILE CARRIES, because it has been the reverse of itself once already. V4-115
// (2026-09-17) read the config-isolation ruling — head configurations and details must never leak
// into other heads, their wrappers, or the core claude binary sessions — as covering transcripts,
// rewrote this class into an un-link that gave every head a REAL private tree, and left cross-head
// resume only as an explicit `-r SESSION_ID` copy (ResumeAcrossHeads). Two days later the operator
// named that a regression: "we implemented a way for any head to join any session from other heads,
// that's now gone" — while the operator's own splice.toml still said share = [..., "projects"] and
// the code treated the spelling as inert. So the ruling stands for CONFIGURATION (settings, rosters,
// hooks, MCP) and transcripts are SHARED SESSION STATE: `projects` is the second dispositioned escape
// from head isolation, beside `sessions`, and — unlike V4-115's un-link — it is decided by the
// operator's share/isolate policy. A head whose policy isolates projects keeps a real tree and
// reaches foreign sessions through ResumeAcrossHeads, as before.
//
// THE KNOWN COST, recorded rather than hidden (V4-115 measured it): a transcript written on one head
// carries that head's model id in its assistant rows, so resuming it on another head — or in the
// vanilla client — prints Claude Code's "Session model <X> could not be restored" once and continues
// on the resuming head's default model. That is the state the operator used under V4-64.
//
// Like sessions/ (SessionRegistryLink) and unlike every operator-authored shared item, a
// pre-existing REAL projects/ dir is Claude Code generated content, so it is merged into the
// global tree and then replaced by the link. NOT by moving (review 2026-09-16): Claude Code appends
// each message with an open-per-append appendFile, and every head on the operator machine has live
// sessions at all times, so a moved <id>.jsonl is split the instant its session writes again — the
// history lands in global, the next message recreates a fragment at the head path. The merge is by
// HARDLINK instead: link(2) gives the global name the SAME inode, so a live writer keeps appending
// to the same bytes before, during and after the swap. The swap itself is two renames — the head
// dir aside to a dotted sibling, the staged symlink onto dst — with a microseconds-wide window in
// which a live append sees ENOENT; that is the window SessionRegistryLink already accepts. After
// the swap a straggler sweep links anything a live writer created between preflight and the aside
// rename (it never got a link) and deletes the aside tree — names only, the inodes live on.
// Both trees live under $HOME, and link(2) refuses across filesystems: that refusal is a
// FileSystemException from the first createLink, caught before anything is renamed, logged, and
// rolled back like any other commit failure.
//
// Three deliberate divergences from the sessions idiom:
//   A. projects/ holds directories by design (one per encoded cwd, session subdirs inside, 20 levels
//      deep on the largest heads), so the preflight is a recursive MERGE — a directory both sides
//      hold is merged into, a global one that is absent is created — rather than an abort;
//   B. a same-named entry on both sides never refuses: the global copy is kept and the head copy is
//      parked beside it as <name>.from-<headKey> (a suffix `--resume` will not list, since it no
//      longer ends in .jsonl), logged once per run. An entry that already IS the global one (same
//      inode, or a symlink with the identical target string) is skipped, which is what makes a
//      retry after a failed swap and the straggler sweep idempotent;
//   C. the head tree holds SYMLINKS too (Claude Code writes subagents/agent-<id>.jsonl as an absolute
//      symlink), recreated in global with the target string copied verbatim. Anything that is not a
//      file, a directory or a symlink, or a parked name already taken, refuses the whole migration
//      before the first link, logged with the entry's name.
// headKey is MaterializeSpec.headKey — the topology key LaunchSpecFactory passes (`codex`, `kimi`)
// — never derived from the config dir's basename (kt-state-paths-single-source forbids a second
// `.claude-` literal); a blank key (test shims) parks as `.from-head`.
package splice.client.wrap

import splice.client.wrap.projects.ProjectsMigrationPlan
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.PathProbe
import splice.core.util.SafeFailureText
import java.nio.file.CopyOption
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlin.io.path.isSymbolicLink

/** The filesystem operations tests must fail deterministically (or observe) without a custom provider. */
internal interface ProjectsFs {
    fun move(source: Path, target: Path, vararg options: CopyOption): Path
    fun createLink(link: Path, existing: Path): Path
    fun createSymbolicLink(link: Path, target: Path): Path
}

private object ProcessProjectsFs : ProjectsFs {
    override fun move(source: Path, target: Path, vararg options: CopyOption): Path =
        Files.move(source, target, *options)

    override fun createLink(link: Path, existing: Path): Path = Files.createLink(link, existing)

    override fun createSymbolicLink(link: Path, target: Path): Path = Files.createSymbolicLink(link, target)
}

// The aside tree's name marker: `.projects.migrating-<uuid>` beside the link. A sweep that could not
// finish leaves it there, and the next launch finds it by this marker.
private const val ASIDE_MARK = ".migrating-"

internal class ProjectsLink(
    headKey: String,
    private val fs: ProjectsFs = ProcessProjectsFs,
) {
    private val parkSuffix = ".from-${headKey.ifBlank { "head" }}"

    /** [link] as the materializer's share loop calls it: link() logs its own declines, this catches
     *  what it THROWS mid-flight (DR-39) so a transcript failure never aborts the rest of the head's
     *  materialize. Lives here rather than in linkShared to keep that loop under its complexity budget. */
    fun linkOrLog(globalProjects: Path, dst: Path, log: LogSink) {
        Cancellables.runCatchingCancellable { link(globalProjects, dst, log) }.exceptionOrNull()?.let { cause ->
            log("[materialize] projects transcripts NOT linked into ${dst.parent} (${SafeFailureText.render(cause)})\n")
        }
    }

    /** The OTHER policy: a head that isolates projects owns a real tree from its first launch, so its
     *  picker has something to list and ResumeAcrossHeads has somewhere to copy into. Creates an
     *  absent directory; a link an earlier launch made becomes a real tree ([ownFromLink]); content of
     *  any other shape is left exactly as found, never deleted to make room. */
    fun ensureOwn(dst: Path, log: LogSink = LogSink(DaemonLog::write)) {
        if (dst.isSymbolicLink()) {
            ownFromLink(dst, log)
            return
        }
        if (Files.exists(dst, NOFOLLOW_LINKS)) return
        val created = Cancellables.runCatchingCancellable { Files.createDirectories(dst) }
        created.exceptionOrNull()?.let { cause ->
            log(
                "[projects] could not create $dst (${SafeFailureText.render(cause)}); Claude Code creates it " +
                    "on first write\n",
            )
        }
    }

    /** A head that isolates projects but whose [dst] is still a link from when it shared them gets an EMPTY private
     *  tree, as a fresh head does, and the shared tree is not touched. Nothing is copied: a session crosses heads the
     *  one way head isolation allows, an explicit `-r` copy with its model rewritten (ResumeAcrossHeads), so the head
     *  needs none of the shared transcripts up front (splice-lead, Oct 13). The empty tree is made beside the link and
     *  renamed over it once the link is removed, so dst is missing only for that instant; a failure puts the link back
     *  and is logged, and the next launch tries again. */
    private fun ownFromLink(dst: Path, log: LogSink) {
        val staged = dst.resolveSibling(".${dst.fileName}.splice-own-${UUID.randomUUID()}")
        var linkTo: Path? = null
        val made = Cancellables.runCatchingCancellable {
            linkTo = Files.readSymbolicLink(dst)
            Files.createDirectory(staged)
            Files.delete(dst)
        }
        val swapped = if (made.isFailure) {
            made
        } else {
            Cancellables.runCatchingCancellable { fs.move(staged, dst, ATOMIC_MOVE) }.map { }
        }
        val cause = swapped.exceptionOrNull()
        if (cause == null) {
            log(
                "[projects] $dst was a link to the shared transcripts; it is replaced by an empty private tree, " +
                    "and an older session comes over with -r <session id>\n",
            )
            return
        }
        val back = linkTo?.takeIf { made.isSuccess }
        Cancellables.discard(
            Cancellables.runCatchingCleanup {
                back?.let { fs.createSymbolicLink(dst, it) }
                Files.deleteIfExists(staged)
            },
            "the link is put back and the empty staged tree removed, best-effort",
        )
        log(
            "[projects] $dst is a link although this head isolates projects, and it could not become a private " +
                "tree (${SafeFailureText.render(cause)}); it still shares transcripts until a launch makes it one\n",
        )
    }

    /** Point [dst] (a head's projects dir) at [globalProjects], CREATING the global tree when it does
     *  not exist yet: it is generated state, and a machine that never ran plain `claude` is exactly
     *  the fresh install where cross-head resume is wanted and its absence least noticeable. */
    fun link(globalProjects: Path, dst: Path, log: LogSink = LogSink(DaemonLog::write)) {
        if (!ensureGlobalProjects(globalProjects, log)) return
        if (dst.isSymbolicLink()) {
            // An unreadable link is relinked below, and a failed relink logs its cause.
            val target = PathProbe.linkTarget(dst)
            if (target == globalProjects) {
                sweepLeftovers(dst, globalProjects, log)
                return
            }
        } else if (Files.exists(dst, NOFOLLOW_LINKS) && !Files.isDirectory(dst, NOFOLLOW_LINKS)) {
            // Unexpected content is preserved, but never SILENTLY (DR-39): the caller's contract is
            // that link() logs its own declines.
            log(
                "[materialize] projects transcripts NOT linked: $dst is unexpected non-directory " +
                    "content, kept as-is; move it aside to share transcripts\n",
            )
            return
        }

        // Build the replacement before touching dst. A creation failure therefore preserves an
        // existing link or real projects directory for the next launch to retry.
        val staged = dst.resolveSibling(".${dst.fileName}.splice-link-${UUID.randomUUID()}")
        fs.createSymbolicLink(staged, globalProjects)
        try {
            if (Files.isDirectory(dst, NOFOLLOW_LINKS)) {
                migrateAndReplace(dst, globalProjects, staged, log)
            } else {
                fs.move(staged, dst, REPLACE_EXISTING, ATOMIC_MOVE)
            }
        } finally {
            // DR-104: the staged leftover is a courtesy — a cleanup throw must never REPLACE the
            // in-flight outcome. Same rule as SessionRegistryLink and LoginInterception's teardown.
            Cancellables.discard(
                Cancellables.runCatchingCleanup { Files.deleteIfExists(staged) },
                "staged-link cleanup: the link outcome must stand",
            )
        }
    }

    /** The global projects tree, created if absent — false when this head must keep private
     *  transcripts. A path that exists but is not a directory (a dangling link included) is left
     *  exactly as found, and a creation failure is reported rather than swallowed. */
    private fun ensureGlobalProjects(globalProjects: Path, log: LogSink): Boolean {
        if (Files.isDirectory(globalProjects, NOFOLLOW_LINKS)) return true
        if (Files.exists(globalProjects, NOFOLLOW_LINKS)) {
            log("[projects] $globalProjects exists but is not a directory, so this head keeps private transcripts\n")
            return false
        }
        val created = Cancellables.runCatchingCancellable { Files.createDirectories(globalProjects) }
        created.exceptionOrNull()?.let { cause ->
            log(
                "[projects] could not create the global projects dir $globalProjects " +
                    "(${SafeFailureText.render(cause)}), so this head keeps private transcripts\n",
            )
        }
        return created.isSuccess
    }

    /** Preflight the whole tree before linking anything, link, swap, then sweep the aside tree.
     *  Declining is never silent (DR-1): a refusal or a rollback lands in the daemon log with its
     *  cause. */
    private fun migrateAndReplace(dst: Path, globalProjects: Path, staged: Path, log: LogSink): Boolean {
        val plan = ProjectsMigrationPlan(parkSuffix)
        val refusal = plan.preflight(dst, globalProjects)
        if (refusal != null) {
            log(
                "[projects] REFUSED to migrate $dst into $globalProjects ($refusal), so " +
                    "this head keeps private transcripts\n",
            )
            return false
        }

        val aside = dst.resolveSibling(".${dst.fileName}$ASIDE_MARK${UUID.randomUUID()}")
        val created = mutableListOf<Path>()
        val commit = Cancellables.runCatchingCancellable {
            plan.apply(fs, created)
            fs.move(dst, aside, ATOMIC_MOVE)
            fs.move(staged, dst, ATOMIC_MOVE)
        }
        if (commit.isFailure) {
            val undone = rollback(dst, aside, created, plan.newGlobalDirs)
            log(
                "[projects] migration of $dst failed " +
                    "(${commit.exceptionOrNull()?.let { SafeFailureText.render(it) }}); " +
                    "$undone; this head keeps private transcripts\n",
            )
            return false
        }
        if (plan.parked.isNotEmpty()) log(plan.parkedLine(globalProjects))
        sweepAside(aside, globalProjects, log)
        return true
    }

    /** A failure BEFORE the aside rename never moved anything: this run's global links and the empty
     *  dirs it created are removed and the head tree was never touched. A failure AT the promotion
     *  puts the aside tree back at dst; its links stay, because they are the same inodes and the
     *  next launch's preflight skips them. Returns the sentence the failure log carries. */
    private fun rollback(dst: Path, aside: Path, created: List<Path>, createdDirs: List<Path>): String {
        val asideDone = Files.isDirectory(aside, NOFOLLOW_LINKS)
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                if (asideDone) {
                    fs.move(aside, dst, ATOMIC_MOVE)
                } else {
                    created.asReversed().forEach { Files.deleteIfExists(it) }
                    createdDirs.asReversed().forEach { dir ->
                        val empty = Files.isDirectory(dir, NOFOLLOW_LINKS) &&
                            Files.newDirectoryStream(dir).use { entries -> !entries.iterator().hasNext() }
                        if (empty) Files.delete(dir)
                    }
                }
            },
            "best-effort rollback after a failed projects migration",
        )
        return if (asideDone) {
            "put the head tree back (its ${created.size} global links stay; a retry skips them)"
        } else {
            "unlinked its ${created.size} global links"
        }
    }

    /** After the promotion the link is serving; the aside tree still holds a name for every inode,
     *  plus anything a live writer created between preflight and the aside rename, which never got a
     *  link. Merge those now under the same rules (an entry already linked is the same inode and is
     *  skipped), then delete the aside tree bottom-up — names only, the inodes live on in global. A
     *  failure here is logged with the aside path and NOT rolled back: the leftover is harmless, and
     *  the next launch finds it by name (sweepLeftovers) and retries. */
    private fun sweepAside(aside: Path, globalProjects: Path, log: LogSink) {
        val plan = ProjectsMigrationPlan(parkSuffix)
        val outcome = Cancellables.runCatchingCancellable {
            plan.preflight(aside, globalProjects)?.let { refusal ->
                throw FileSystemException(aside.toString(), null, refusal)
            }
            plan.apply(fs, mutableListOf())
            deleteTree(aside)
        }
        outcome.exceptionOrNull()?.let { cause ->
            log(
                "[projects] straggler sweep of $aside into $globalProjects stopped " +
                    "(${SafeFailureText.render(cause)}); the head link is already serving; " +
                    "the leftover tree is retried at the next launch\n",
            )
        }
        if (outcome.isSuccess && plan.parked.isNotEmpty()) log(plan.parkedLine(globalProjects))
    }

    /** A previous launch's aside tree whose sweep could not finish (logged then), found by its marker
     *  beside the link and swept the same way. */
    private fun sweepLeftovers(dst: Path, globalProjects: Path, log: LogSink) {
        val parent = dst.parent ?: return
        val leftovers = Cancellables.runCatchingCancellable {
            Files.newDirectoryStream(parent, ".${dst.fileName}$ASIDE_MARK*").use { stream -> stream.toList() }
        }.onFailure { failure ->
            log(
                "[projects] leftover asides beside $dst NOT swept: $parent unlistable " +
                    "(${SafeFailureText.render(failure)})\n",
            )
        }.getOrDefault(emptyList())
        leftovers.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { sweepAside(it, globalProjects, log) }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}
