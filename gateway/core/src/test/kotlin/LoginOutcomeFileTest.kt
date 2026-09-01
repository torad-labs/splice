// WALLS for the in-session login receipt (2026-08-01). This is the ONLY channel by which a
// DETACHED sign-in can tell the session what happened — and for kimi it is the only channel at
// all, since an RFC 8628 device flow has no browser redirect to confirm in (the reason opencode
// and Kilo Code both confirm in-client rather than via a callback page).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.launch.LoginOutcomeFile
import java.nio.file.Files
import java.nio.file.Path

class LoginOutcomeFileTest {

    @Test
    fun `a written receipt is read back once, then consumed`(@TempDir tmp: Path) {
        LoginOutcomeFile.write(tmp, "claudex", "signed in — this session is using the new credentials.")
        assertEquals(
            "signed in — this session is using the new credentials.",
            LoginOutcomeFile.consume(tmp, "claudex"),
        )
        assertNull(
            LoginOutcomeFile.consume(tmp, "claudex"),
            "a confirmation shows ONCE — otherwise every later prompt re-announces an old login",
        )
    }

    /** The failure receipt matters more than the success one: today a detached login that fails
     *  says nothing at all, and the user waits on a session that will never work. */
    @Test
    fun `a failure receipt reaches the session too`(@TempDir tmp: Path) {
        LoginOutcomeFile.write(tmp, "claude-kimi", "sign-in did not complete.")
        assertEquals("sign-in did not complete.", LoginOutcomeFile.consume(tmp, "claude-kimi"))
    }

    /** A login from an hour ago must not confirm an unrelated prompt today. */
    @Test
    fun `a stale receipt is discarded, not shown`(@TempDir tmp: Path) {
        LoginOutcomeFile.write(tmp, "claudex", "signed in")
        val hourLater = System.currentTimeMillis() + 60 * 60 * 1000
        assertNull(LoginOutcomeFile.consume(tmp, "claudex", nowMs = hourLater))
        assertFalse(
            Files.exists(LoginOutcomeFile.pathFor(tmp, "claudex")),
            "stale receipts are deleted on read — a file left behind would surface later",
        )
    }

    /** Heads must never read each other's confirmations. */
    @Test
    fun `receipts are per head`(@TempDir tmp: Path) {
        LoginOutcomeFile.write(tmp, "claudex", "codex signed in")
        LoginOutcomeFile.write(tmp, "claude-grok", "grok signed in")
        assertEquals("codex signed in", LoginOutcomeFile.consume(tmp, "claudex"))
        assertEquals("grok signed in", LoginOutcomeFile.consume(tmp, "claude-grok"))
    }

    /** A head name reaches this from the topology, so it must not be able to escape the dir. */
    @Test
    fun `a hostile head name cannot escape the state dir`(@TempDir tmp: Path) {
        val path = LoginOutcomeFile.pathFor(tmp, "../../etc/passwd")
        assertEquals(tmp, path.parent, "the receipt must stay inside the state dir")
        assertFalse(path.toString().contains(".."))
    }

    /** DR-179: the receipt name COLLAPSED every character outside [A-Za-z0-9_-] to a literal
     *  underscore, so two heads whose keys differ only there resolved to ONE file. Whichever
     *  signed in last, the other head's next prompt consumed the line and announced a result for a
     *  login it never ran — and deleted it, so the head that DID sign in learned nothing. The arm
     *  above covered the traversal escape, which that sanitizer genuinely stopped; it could not see
     *  a collision, because collapsing is exactly what it was written to do. */
    @Test
    fun `heads whose keys differ only in a sanitized character do not share a receipt - DR-179`(
        @TempDir tmp: Path,
    ) {
        assertNotEquals(
            LoginOutcomeFile.pathFor(tmp, "gpt-5.6"),
            LoginOutcomeFile.pathFor(tmp, "gpt-5_6"),
            "distinct head keys must not resolve to one receipt file",
        )
        LoginOutcomeFile.write(tmp, "gpt-5.6", "gpt-5.6 signed in")
        assertNull(
            LoginOutcomeFile.consume(tmp, "gpt-5_6"),
            "a head must not announce — or eat — the receipt another head's login wrote",
        )
        assertEquals(
            "gpt-5.6 signed in",
            LoginOutcomeFile.consume(tmp, "gpt-5.6"),
            "the head that actually signed in must still find its own receipt",
        )
    }

    /** The arm above names ONE collision; the invariant is injectivity, so this pins the property
     *  itself over the keys that break each half-measure. `a.b` vs `a_b` breaks a collapsing
     *  sanitizer. `a.b` vs `a_2eb` breaks an escape whose MARKER is not itself escaped — a head
     *  keyed the escaped spelling collides with the key that escapes to it, which is why `_` maps
     *  to `_5f`. `a.b` vs `a2eb` breaks a markerless hex escape. Distinct keys in, distinct
     *  receipts out, or one head eats another's confirmation. */
    @Test
    fun `distinct head keys map to distinct receipts across the collision set - DR-179`(@TempDir tmp: Path) {
        // The ASCII list alone left the injectivity claim pinned over a subset, so two more groups.
        //
        // MULTIBYTE, because every non-ASCII byte is NEGATIVE as a signed Byte and the escape leans
        // on "%02x" rendering it as exactly two hex digits — verified on this JDK: -30 -> "e2",
        // -128 -> "80", -61 -> "c3", NOT a sign-extended "ffffffe2".
        //
        // The last pair is what makes the zero-PADDING load-bearing rather than decorative, and it
        // is the only pair here that a plain "%x" would collide: U+0001 then "23" escapes to
        // _01 2 3, while U+0012 then "3" escapes to _12 3 — distinct only because both escapes are
        // two digits wide. Drop the padding and both become "_123". A fixed-width escape following
        // an always-unsafe "_" is the whole reason distinct keys cannot collide, and without this
        // pair nothing in the suite fails when that width stops being fixed.
        val keys = listOf(
            "a.b", "a_b", "a-b", "a b", "a/b", "a_2eb", "a2eb", "a__b", "_ab", "ab_", "a..b", "a",
            "gpt‑5", "gpt-5", "café", "cafe",
            "\u0001" + "23", "\u0012" + "3",
        )
        val paths = keys.map { LoginOutcomeFile.pathFor(tmp, it) }
        assertEquals(
            keys.size,
            paths.toSet().size,
            "every distinct head key needs its own receipt; collided: " + keys.zip(paths).groupBy { it.second }
                .filterValues { it.size > 1 }.values.map { pair -> pair.map { it.first } },
        )
    }

    /** Newlines would break the one-line shell read-back in the hook. */
    @Test
    fun `a multi-line message is flattened to one line`(@TempDir tmp: Path) {
        LoginOutcomeFile.write(tmp, "claudex", "line one\nline two")
        val read = LoginOutcomeFile.consume(tmp, "claudex")
        assertEquals("line one line two", read)
        assertFalse(read!!.contains('\n'), "the hook reads ONE line; an embedded newline truncates it")
    }

    @Test
    fun `no receipt means no confirmation`(@TempDir tmp: Path) {
        assertNull(LoginOutcomeFile.consume(tmp, "never-logged-in"))
    }
}
