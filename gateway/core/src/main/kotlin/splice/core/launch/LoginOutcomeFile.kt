// NEW: (login UX, 2026-08-01) the in-session login receipt.
//
// THE PROBLEM. `/login` runs the sign-in DETACHED, so every message the flow prints — "signed in",
// "the code expired", the kimi user code — goes to /dev/null. The session that asked for the login
// therefore never learns what happened. That is what "kimi and grok don't confirm the login" is:
// not a missing browser page but a missing channel back.
//
// A browser page CANNOT be the answer for every head. kimi is an RFC 8628 device flow: the browser
// stays on the provider's own page and there is no redirect target for splice to render. Both
// opencode's Kimi plugin and Kilo Code (which deliberately MIGRATED from browser-callback OAuth to
// device auth, PR #4178) confirm in-client for exactly this reason — a status surface, not a
// redirect. This file is that surface for splice.
//
// SHAPE. The login writes one short line; the head's /login hook reads and deletes it on the next
// prompt. Deliberately NOT a socket or a daemon call: the login may outlive the session, run
// before the daemon is up, or be run from a plain terminal, and a file survives all three. Stale
// receipts self-expire so a login from an hour ago cannot confirm today's prompt.
package splice.core.launch

import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

public object LoginOutcomeFile {

    /** Older than this and the receipt is ignored: a login the user has since forgotten about must
     *  not surface as a confirmation for an unrelated prompt. */
    private const val FRESH_WINDOW_MS: Long = 10 * 60 * 1000

    public fun pathFor(stateDir: Path, head: String): Path =
        stateDir.resolve("login-outcome-${head.replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt")

    /** Record the outcome. Best-effort by construction: a login that SUCCEEDED must never be
     *  reported as failed because its receipt could not be written. */
    public fun write(stateDir: Path, head: String, message: String) {
        val receipt = Cancellables.runCatchingCancellable {
            val path = pathFor(stateDir, head)
            // IO-007: temp-file + ATOMIC_MOVE (SecureFile, the codebase's one atomic-write
            // primitive) instead of TRUNCATE_EXISTING — two simultaneous receipts for one head no
            // longer race to truncate each other mid-write; the last write to land wins cleanly.
            SecureFile.writeAtomic0600(path, message.replace('\n', ' ').trim() + "\n")
        }
        Cancellables.discard(receipt, "the login itself already succeeded or failed; the receipt is a courtesy")
    }

    /** Read and CONSUME a fresh receipt, or null. Consuming is the point: a confirmation is shown
     *  once, not on every prompt for the rest of the session. */
    public fun consume(stateDir: Path, head: String, nowMs: Long = System.currentTimeMillis()): String? =
        Cancellables.runCatchingCancellable {
            val path = pathFor(stateDir, head)
            if (!Files.isRegularFile(path)) return@runCatchingCancellable null
            val age = nowMs - Files.getLastModifiedTime(path).toMillis()
            val text = Files.readString(path).trim()
            Files.deleteIfExists(path) // consumed either way — a stale receipt must not linger
            text.takeIf { it.isNotEmpty() && age in 0..FRESH_WINDOW_MS }
        }.getOrNull()
}
