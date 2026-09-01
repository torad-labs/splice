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
     *  not surface as a confirmation for an unrelated prompt. `internal` (DR-103): the generated
     *  /login hook is the PRODUCTION consumer of the receipt and enforces the same window in bash
     *  (find -mmin +N) — one definition, or the two readers drift. */
    internal const val FRESH_WINDOW_MINUTES: Long = 10
    private const val FRESH_WINDOW_MS: Long = FRESH_WINDOW_MINUTES * 60 * 1000

    /** DR-179: the receipt path, and it has to be INJECTIVE.
     *
     *  The old sanitizer replaced every character outside A-Za-z0-9_- with a literal underscore, so
     *  head `a.b` and head `a_b` both resolved to login-outcome-a_b.txt. Two heads then shared one
     *  receipt: whichever signed in last, the OTHER head's next prompt consumed the line and
     *  announced its result — including announcing a success for a login that head never ran, and
     *  deleting it so the head that did run learned nothing. The existing arm covered the
     *  ../../etc/passwd escape, which the old sanitizer did stop; collision it could not see,
     *  because a collapse is exactly what that sanitizer was written to do.
     *
     *  Escaping instead of collapsing: `_` becomes `_5f` and every other disallowed byte becomes
     *  `_<2-hex>` over its UTF-8 bytes. That is injective by construction — the escape marker is
     *  itself escaped, so no two distinct keys can produce one name — while staying inside
     *  [A-Za-z0-9_-], so the traversal guarantee the old arm pins is unchanged: no separator and no
     *  dot survives, and `..` cannot be spelled. */
    public fun pathFor(stateDir: Path, head: String): Path =
        stateDir.resolve("login-outcome-${escapeHeadKey(head)}.txt")

    private fun escapeHeadKey(head: String): String = buildString {
        head.toByteArray(Charsets.UTF_8).forEach { byte ->
            val ch = byte.toInt().toChar()
            val safe = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-'
            if (safe) append(ch) else append('_').append("%02x".format(byte))
        }
    }

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
