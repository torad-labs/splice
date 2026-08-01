// WALLS for the in-session login receipt (2026-08-01). This is the ONLY channel by which a
// DETACHED sign-in can tell the session what happened — and for kimi it is the only channel at
// all, since an RFC 8628 device flow has no browser redirect to confirm in (the reason opencode
// and Kilo Code both confirm in-client rather than via a callback page).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
