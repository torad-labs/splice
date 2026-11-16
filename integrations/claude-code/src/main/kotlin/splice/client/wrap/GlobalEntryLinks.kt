// NEW: split out of HeadCommandsDir.kt (gpt-speed, 2026-11-16) — the operator's shared ~/.claude/<item> re-linked entry
// by entry into a head's REAL <item> dir. Two dirs need it now: commands (login.md lives there) and skills (/fast and
// /ultrafast live there on a head signed in with ChatGPT). One body, so the DR-39 probes below hold for both, and one
// stage-and-swap (HeadRealDir) that turns either dir from a whole-dir link into the real dir they are linked into.
package splice.client.wrap

import splice.core.util.Cancellables
import splice.core.util.PathProbe
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

/** A head's own REAL dir: the swap both the commands dir (login.md) and the skills dir (/fast, /ultrafast) make. */
internal object HeadRealDir {
    /** Fills [configDir]'s real [name] dir through [fill] and returns. A whole-dir symlink there (the share of the
     *  person's own dir) becomes a real dir, staged COMPLETE beside the link first, so only the unlink and the rename
     *  are left once it is whole and a working dir is never seen missing or torn (DR-39: the old
     *  delete-then-populate lost it whenever a step after the delete failed). A stale stage from a crashed attempt is
     *  a createDirectories no-op (a dir) or a loud failure before the link is touched (a file). Without a link, [fill]
     *  writes into the live dir. */
    inline fun write(configDir: Path, name: String, fill: (Path) -> Unit) {
        val dst = configDir.resolve(name)
        val linked = dst.isSymbolicLink()
        val staged = configDir.resolve(".$name.staged-${ProcessHandle.current().pid()}")
        val target = Files.createDirectories(if (linked) staged else dst)
        fill(target)
        if (linked) {
            Files.delete(dst)
            Files.move(target, dst)
        }
    }
}

/** Links every entry of a [global] dir into a head's own real dir, except the names in [owned], which the head writes
 *  itself. */
internal class GlobalEntryLinks(private val owned: Set<String>) {

    fun linkInto(dst: Path, global: Path) {
        // DR-39 redo 2 (codex): `isDirectory` was an absence PRE-gate, and it lies false for an
        // untraversable parent — the login leg then "succeeded" with login.md while every shared
        // command silently vanished. Opening the stream is the only honest probe (class law): a
        // no-commands operator is the one quiet skip, proven by NoSuchFileException with no
        // NOFOLLOW entry; everything else present-but-unreadable (denied parent, dangling link,
        // regular file) throws into the caller's existing loud leg.
        val entries = Cancellables.runCatchingCancellable { Files.newDirectoryStream(global) }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                    !Files.exists(global, NOFOLLOW_LINKS)
                if (genuinelyAbsent) return
                throw failure
            }
        entries.use { stream ->
            stream.filter { it.fileName.toString() !in owned }.forEach { linkOneInto(dst, it) }
        }
    }

    private fun linkOneInto(dir: Path, src: Path) {
        val dst = dir.resolve(src.fileName.toString())
        val present = Files.exists(dst, NOFOLLOW_LINKS)
        // Steady state first (DR-39): commands reconcile on EVERY launch, and most launches find
        // the link already correct — the old unconditional delete-and-recreate churned the inode
        // and opened a window where a crash between the two syscalls left the command MISSING. A
        // correct link is a no-op; a real directory is operator content and stays; only a stale
        // file or wrong-target link is replaced, via a staged sibling published in ONE atomic
        // rename so no reader ever sees the name absent. A staged-name collision (crashed attempt
        // debris, or a fault-injection double) fails BEFORE dst is touched.
        val alreadyCorrect = present && dst.isSymbolicLink() &&
            // An unreadable link is not "already correct"; it is replaced below.
            PathProbe.linkTarget(dst) == src
        when {
            alreadyCorrect || (present && dst.isDirectory(NOFOLLOW_LINKS)) -> Unit
            !present -> Files.createSymbolicLink(dst, src)
            else -> {
                val staged = dir.resolve(".${dst.fileName}.staged-${ProcessHandle.current().pid()}")
                Files.createSymbolicLink(staged, src)
                Files.move(staged, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
