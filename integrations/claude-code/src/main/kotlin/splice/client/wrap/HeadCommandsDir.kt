// NEW: split out of LoginInterception.kt (2026-09-02) — the head's REAL commands dir — the
// operator's shared ~/.claude/commands re-linked entry by entry, and the /login command file that
// is the reason the dir must be real rather than a whole-dir symlink. Stage-and-swap throughout:
// a working commands dir is never observable missing or torn (DR-39, DR-180).
package splice.client.wrap

import splice.client.login.LoginHookScripts
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
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
                    "(${leg.exceptionOrNull()?.let(SafeFailureText::render)}), so this head's own commands dir is " +
                    "missing the operator's entries\n",
            )
        }
    }

    fun write(configDir: Path, signInLabel: String, globalCommands: Path?, sentinel: String) {
        // A whole-dir commands symlink must become the real dir login.md lives in (HeadRealDir, DR-39).
        HeadRealDir.write(configDir, COMMANDS_DIR) { target ->
            if (globalCommands != null) linkGlobalCommandsInto(target, globalCommands)
            writeLoginMd(target, signInLabel, sentinel)
        }
    }

    private fun writeLoginMd(target: Path, signInLabel: String, sentinel: String) {
        // DR-180: stage-and-swap, not truncate-in-place. When the dir was a link the whole staged
        // DIRECTORY is moved into place by HeadRealDir, so login.md was already published atomically; the
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
    }

    private fun linkGlobalCommandsInto(dst: Path, globalCommands: Path) {
        GlobalEntryLinks(setOf(LOGIN_MD)).linkInto(dst, globalCommands)
    }
}
