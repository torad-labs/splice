// NEW: split out of LoginInterception.kt (2026-09-02) — the head's REAL commands dir — the
// operator's shared ~/.claude/commands re-linked entry by entry, and the /login command file that
// is the reason the dir must be real rather than a whole-dir symlink. Stage-and-swap throughout:
// a working commands dir is never observable missing or torn (DR-39, DR-180).
package splice.core.launch

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

internal object HeadCommandsDir {
    private const val COMMANDS_DIR = "commands"
    private const val LOGIN_MD = "login.md"

    /** DR-39 redo (codex): commands reconciliation is not login plumbing. A client-auth head
     *  (blank loginCommand) still shares the operator's commands, and the materializer EXEMPTS
     *  commands' real-dir decline on the promise that THIS file reconciles them — so for a
     *  blank-login head the promise must hold before wire()'s login-specific early return. Only
     *  the head's-own-REAL-dir shape needs work: a whole-dir symlink already IS the share, and an
     *  absent dir is linked whole by the materializer. No login.md is written — a /login command
     *  on a client-auth head would be wrong. */
    fun reconcileBlankLogin(configDir: Path, globalCommands: Path?, log: LogSink) {
        if (globalCommands == null) return
        val dst = configDir.resolve(COMMANDS_DIR)
        if (!Files.isDirectory(dst, NOFOLLOW_LINKS) || dst.isSymbolicLink()) return
        val leg = Cancellables.runCatchingCancellable { linkGlobalCommandsInto(dst, globalCommands) }
        if (leg.isFailure) {
            log(
                "[login] shared commands NOT reconciled into $configDir " +
                    // SAFE-RENDER-EXEMPT[2026-08-31]: staged commands dir link leg — a FileSystemException over paths this code authored, never content
                    "(${leg.exceptionOrNull()?.message}) — this head's own commands dir is " +
                    "missing the operator's entries\n",
            )
        }
    }

    fun write(configDir: Path, signInLabel: String, globalCommands: Path?, sentinel: String) {
        val dst = configDir.resolve(COMMANDS_DIR)
        val symlinked = dst.isSymbolicLink()
        // A whole-dir commands symlink must become the real dir login.md lives in — but the old
        // delete-then-createDirectories-then-populate lost the WORKING commands dir whenever a step
        // after the delete failed (DR-39: ENOSPC/EPERM mid-populate). The real dir is now staged
        // COMPLETE beside the link first; only unlink+rename remain after it is whole. A stale
        // stage from a crashed attempt is a createDirectories no-op (dir) or a loud pre-delete
        // failure (file) that leaves the link untouched.
        val target = if (symlinked) {
            Files.createDirectories(configDir.resolve(".$COMMANDS_DIR.staged-${ProcessHandle.current().pid()}"))
        } else {
            Files.createDirectories(dst)
        }
        if (globalCommands != null) linkGlobalCommandsInto(target, globalCommands)
        // DR-180: stage-and-swap, not truncate-in-place. When `symlinked` is true the whole staged
        // DIRECTORY is moved into place below, so login.md was already published atomically; the
        // other branch writes straight into the LIVE commands dir, where Files.writeString truncates
        // the running head's /login command and refills it — a crash or a concurrent read in that
        // window yields a half-written or empty command file, and following a pre-planted symlink at
        // that name truncates whatever it points at. HookScriptFiles.writeHookScript has staged
        // through a unique temp + ATOMIC_MOVE since DR-31 for exactly these two reasons; login.md
        // was the sibling that never got it. SecureFile is the codebase's one atomic-write
        // primitive (temp + ATOMIC_MOVE, perms before content) — 0600 is right for a file only this
        // operator's own head reads.
        SecureFile.writeAtomic0600(
            target.resolve(LOGIN_MD),
            LoginHookScripts.loginCommandMd(signInLabel, sentinel),
        )
        if (symlinked) {
            Files.delete(dst)
            Files.move(target, dst)
        }
    }

    private fun linkGlobalCommandsInto(dst: Path, globalCommands: Path) {
        // DR-39 redo 2 (codex): `isDirectory` was an absence PRE-gate, and it lies false for an
        // untraversable parent — the login leg then "succeeded" with login.md while every shared
        // command silently vanished. Opening the stream is the only honest probe (class law): a
        // no-commands operator is the one quiet skip, proven by NoSuchFileException with no
        // NOFOLLOW entry; everything else present-but-unreadable (denied parent, dangling link,
        // regular file) throws into the caller's existing loud leg.
        val entries = Cancellables.runCatchingCancellable { Files.newDirectoryStream(globalCommands) }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                    !Files.exists(globalCommands, NOFOLLOW_LINKS)
                if (genuinelyAbsent) return
                throw failure
            }
        entries.use { stream ->
            stream.filter { it.fileName.toString() != LOGIN_MD }.forEach { linkOneInto(dst, it) }
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
            Cancellables.runCatchingCancellable { Files.readSymbolicLink(dst) }.getOrNull() == src
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
