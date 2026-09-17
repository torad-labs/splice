// NEW: cross-head --resume (2026-09-16). Claude Code keeps every transcript under
// $CLAUDE_CONFIG_DIR/projects/<encoded-cwd>/<sessionId>.jsonl (plus a <sessionId>/ subdir of
// subagent transcripts and tool results), and `--resume` finds a session by listing exactly that
// tree. Each head launches with its own CLAUDE_CONFIG_DIR, so a session started on claudex is
// invisible to claude-kimi's resume picker — splice keeps no server-side state a resume needs, so
// filesystem visibility IS the feature. Linking every head's projects/ at the operator's global
// ~/.claude/projects makes one session resumable from any head when an account runs dry.
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
package splice.core.launch

import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
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

/** Everything the preflight decided before the first link: the global dirs to create, the hardlinks
 *  (global name -> head file) and symlinks (global name -> target string) in walk order with every
 *  collision already redirected to its parked name, and the parked names for the log. */
private class MigrationPlan {
    val newGlobalDirs = mutableListOf<Path>()
    val hardLinks = mutableListOf<Pair<Path, Path>>()
    val symLinks = mutableListOf<Pair<Path, Path>>()
    val parked = mutableListOf<Path>()

    /** Dirs first, then every link in order, appending each global name to [created] as it lands so a
     *  failure part-way knows exactly what to unlink. */
    fun apply(fs: ProjectsFs, created: MutableList<Path>) {
        newGlobalDirs.forEach { Files.createDirectories(it) }
        hardLinks.forEach { (link, existing) -> created.add(fs.createLink(link, existing)) }
        symLinks.forEach { (link, target) -> created.add(fs.createSymbolicLink(link, target)) }
    }

    fun parkedLine(globalProjects: Path): String =
        "[projects] ${parked.size} entries already existed in $globalProjects — kept the global copy, " +
            "parked this head's beside it: " +
            parked.joinToString(", ") { globalProjects.relativize(it).toString() } + "\n"
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
            // SAFE-RENDER-EXEMPT[2026-09-16]: link does path work only — the failure names a directory, never transcript content
            log("[materialize] projects transcripts NOT linked into ${dst.parent} (${cause.message})\n")
        }
    }

    /** Point [dst] (a head's projects dir) at [globalProjects], CREATING the global tree when it does
     *  not exist yet: it is generated state, and a machine that never ran plain `claude` is exactly
     *  the fresh install where cross-head resume is wanted and its absence least noticeable. */
    fun link(globalProjects: Path, dst: Path, log: LogSink = LogSink(DaemonLog::write)) {
        if (!ensureGlobalProjects(globalProjects, log)) return
        if (dst.isSymbolicLink()) {
            val target = Cancellables.runCatchingCancellable { Files.readSymbolicLink(dst) }.getOrNull()
            if (target == globalProjects) {
                sweepLeftovers(dst, globalProjects, log)
                return
            }
        } else if (Files.exists(dst, NOFOLLOW_LINKS) && !Files.isDirectory(dst, NOFOLLOW_LINKS)) {
            // Unexpected content is preserved, but never SILENTLY (DR-39): the caller's contract is
            // that link() logs its own declines.
            log(
                "[materialize] projects transcripts NOT linked — $dst is unexpected non-directory " +
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
                "staged-link cleanup — the link outcome must stand",
            )
        }
    }

    /** The global projects tree, created if absent — false when this head must keep private
     *  transcripts. A path that exists but is not a directory (a dangling link included) is left
     *  exactly as found, and a creation failure is reported rather than swallowed. */
    private fun ensureGlobalProjects(globalProjects: Path, log: LogSink): Boolean {
        if (Files.isDirectory(globalProjects, NOFOLLOW_LINKS)) return true
        if (Files.exists(globalProjects, NOFOLLOW_LINKS)) {
            log("[projects] $globalProjects exists but is not a directory — this head keeps private transcripts\n")
            return false
        }
        val created = Cancellables.runCatchingCancellable { Files.createDirectories(globalProjects) }
        created.exceptionOrNull()?.let { cause ->
            log(
                "[projects] could not create the global projects dir $globalProjects " +
                    "(${SafeFailureText.render(cause)}) — this head keeps private transcripts\n",
            )
        }
        return created.isSuccess
    }

    /** Preflight the whole tree before linking anything, link, swap, then sweep the aside tree.
     *  Declining is never silent (DR-1): a refusal or a rollback lands in the daemon log with its
     *  cause. */
    private fun migrateAndReplace(dst: Path, globalProjects: Path, staged: Path, log: LogSink): Boolean {
        val plan = MigrationPlan()
        val refusal = preflight(dst, globalProjects, plan)
        if (refusal != null) {
            // SAFE-RENDER-EXEMPT[2026-09-16]: `refusal` here is a String this class composes from a file NAME and a fixed phrase, not a throwable — no exception text reaches it
            log(
                "[projects] REFUSED to migrate $dst into $globalProjects ($refusal) — " +
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
                    "(${commit.exceptionOrNull()?.let { SafeFailureText.render(it) }}) — " +
                    "$undone; this head keeps private transcripts\n",
            )
            return false
        }
        if (plan.parked.isNotEmpty()) log(plan.parkedLine(globalProjects))
        sweepAside(aside, globalProjects, log)
        return true
    }

    /** Walk [head] against [global] filling [plan]; the first unexpected entry is the decline cause. */
    private fun preflight(head: Path, global: Path, plan: MigrationPlan): String? {
        val entries = Files.newDirectoryStream(head).use { stream ->
            stream.toList().sortedBy { it.fileName.toString() }
        }
        for (entry in entries) {
            val target = global.resolve(entry.fileName)
            val cause = when {
                entry.isSymbolicLink() -> preflightSymlink(entry, target, plan)
                Files.isDirectory(entry, NOFOLLOW_LINKS) -> preflightDir(entry, target, plan)
                Files.isRegularFile(entry, NOFOLLOW_LINKS) -> preflightFile(entry, target, plan)
                else -> "unexpected entry '${entry.fileName}' (neither file, directory nor symlink)"
            }
            if (cause != null) return cause
        }
        return null
    }

    // Divergence A: a directory both sides hold is merged into; an absent global one is created.
    private fun preflightDir(entry: Path, target: Path, plan: MigrationPlan): String? {
        if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isDirectory(target, NOFOLLOW_LINKS)) {
            return "'${entry.fileName}' is a directory here but not in the global projects"
        }
        if (!Files.exists(target, NOFOLLOW_LINKS)) plan.newGlobalDirs.add(target)
        return preflight(entry, target, plan)
    }

    // Divergence B: the global name gets this file's inode when free; a same-named file that already
    // IS this inode is done; any other collision parks the inode under the head's suffix instead.
    private fun preflightFile(entry: Path, target: Path, plan: MigrationPlan): String? {
        val parked = target.resolveSibling("${target.fileName}$parkSuffix")
        return when {
            !Files.exists(target, NOFOLLOW_LINKS) -> null.also { plan.hardLinks.add(target to entry) }
            Files.isSameFile(target, entry) -> null
            !Files.exists(parked, NOFOLLOW_LINKS) -> null.also {
                plan.hardLinks.add(parked to entry)
                plan.parked.add(parked)
            }
            Files.isSameFile(parked, entry) -> null
            else -> "'${target.fileName}' and its parked copy '${parked.fileName}' both already exist globally"
        }
    }

    // Divergence C: a symlink is recreated with its target string verbatim; one already there with
    // the identical target is done; a different one parks, like a file.
    private fun preflightSymlink(entry: Path, target: Path, plan: MigrationPlan): String? {
        val dest = Files.readSymbolicLink(entry)
        val parked = target.resolveSibling("${target.fileName}$parkSuffix")
        return when {
            !Files.exists(target, NOFOLLOW_LINKS) -> null.also { plan.symLinks.add(target to dest) }
            sameLink(target, dest) -> null
            !Files.exists(parked, NOFOLLOW_LINKS) -> null.also {
                plan.symLinks.add(parked to dest)
                plan.parked.add(parked)
            }
            sameLink(parked, dest) -> null
            else -> "'${target.fileName}' and its parked copy '${parked.fileName}' both already exist globally"
        }
    }

    private fun sameLink(path: Path, dest: Path): Boolean =
        path.isSymbolicLink() && Files.readSymbolicLink(path) == dest

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
                    createdDirs.asReversed().forEach { if (isEmptyDir(it)) Files.delete(it) }
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
        val plan = MigrationPlan()
        val outcome = Cancellables.runCatchingCancellable {
            preflight(aside, globalProjects, plan)?.let { refusal ->
                throw FileSystemException(aside.toString(), null, refusal)
            }
            plan.apply(fs, mutableListOf())
            deleteTree(aside)
        }
        outcome.exceptionOrNull()?.let { cause ->
            log(
                "[projects] straggler sweep of $aside into $globalProjects stopped " +
                    "(${SafeFailureText.render(cause)}) — the head link is already serving; " +
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
        }.getOrDefault(emptyList())
        leftovers.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { sweepAside(it, globalProjects, log) }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private fun isEmptyDir(dir: Path): Boolean =
        Files.isDirectory(dir, NOFOLLOW_LINKS) && Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }
}
