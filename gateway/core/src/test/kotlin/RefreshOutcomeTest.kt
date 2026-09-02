// NEW (discipline L3): pins for the sealed refresh outcome and its single SPI flatten —
// Refreshed passes credentials through silently; every failure branch logs EXACTLY one line
// that names its distinct story (dead token vs transport blip vs corrupt file vs not-logged-in
// were previously one indistinguishable null — the 2026-07-18 incident shape).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.auth.RefreshOutcome
import splice.core.util.LogSink
import java.io.IOException

class RefreshOutcomeTest {

    private val creds = Credentials.Bearer("tok", null)

    private fun logsOf(outcome: RefreshOutcome): Pair<Credentials?, List<String>> {
        val lines = mutableListOf<String>()
        val out = outcome.credentialsOrNull("test-auth") { lines.add(it) }
        return out to lines
    }

    @Test
    fun `refreshed passes credentials through without logging`() {
        val (out, lines) = logsOf(RefreshOutcome.Refreshed(creds))
        assertEquals(creds, out)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `every failure branch nulls with exactly one tagged distinguishable line`() {
        val branches = mapOf<RefreshOutcome, String>(
            RefreshOutcome.NoCredentialsFile to "no credential file",
            RefreshOutcome.NoRefreshToken to "no refresh token",
            RefreshOutcome.Rejected("invalid_grant") to "invalid_grant",
            RefreshOutcome.ReadFailed(IOException("corrupt")) to "read failed",
            RefreshOutcome.TransportFailed(IOException("dns")) to "transport failed",
            RefreshOutcome.PersistFailed.Write(IOException("disk full")) to "persist failed",
            RefreshOutcome.PersistFailed.UnreadableAfterWrite to "persist failed",
        )
        for ((outcome, marker) in branches) {
            val (out, lines) = logsOf(outcome)
            assertNull(out, "$outcome must flatten to null")
            assertEquals(1, lines.size, "$outcome must log exactly once")
            assertTrue(lines.single().startsWith("[test-auth]"), "$outcome line must carry the tag")
            assertTrue(
                lines.single().contains(marker, ignoreCase = true),
                "$outcome line must tell its own story (wanted '$marker' in: ${lines.single()})",
            )
        }
    }

    @Test
    fun `failure lines are pairwise distinct so operators can tell the stories apart`() {
        val all = listOf(
            RefreshOutcome.NoCredentialsFile,
            RefreshOutcome.NoRefreshToken,
            RefreshOutcome.Rejected("r"),
            RefreshOutcome.ReadFailed(IOException("x")),
            RefreshOutcome.TransportFailed(IOException("y")),
            RefreshOutcome.PersistFailed.Write(IOException("p")),
            RefreshOutcome.PersistFailed.UnreadableAfterWrite,
        ).map { logsOf(it).second.single() }
        assertEquals(all.size, all.toSet().size)
    }
}

// DR-151: PersistFailed used to carry a PRE-RENDERED String, so the sanitizer decision was made at
// the call site and the DR-140 wall — which can only see call sites it is looking at — could not
// stop a future one from baking raw throwable text into the field. The type now carries the
// throwable whole and the sink owns the render, which makes the hazard inexpressible rather than
// merely absent. These arms hold that property at the SINK, where it is now decided.
class PersistFailedRenderTest {

    private fun logsOf(outcome: RefreshOutcome): List<String> {
        val lines = mutableListOf<String>()
        outcome.credentialsOrNull("test-auth", LogSink { lines.add(it) })
        return lines
    }

    // The hostile shape: a throwable whose own toString() carries the secret. SafeFailureText
    // allowlists a small set of I/O types and renders everything else as a withheld marker, so a
    // vendor or JSON exception that embedded a token cannot reach the log through this branch.
    @Test
    fun `a non-allowlisted throwable carrying a secret is withheld at the persist sink - DR-151`() {
        val secret = "sk-live-DR151-must-never-appear"
        val hostile = object : RuntimeException("boom") {
            override fun toString(): String = "HostileException: $secret"
        }
        val line = logsOf(RefreshOutcome.PersistFailed.Write(hostile)).single()
        assertFalse(line.contains(secret), "the persist line must never quote the throwable's own text: $line")
        assertTrue(line.contains("withheld"), "it must say the message was withheld, not go silent: $line")
        assertTrue(line.contains("persist failed"), "and still tell the persist story: $line")
    }

    // The other half: an ALLOWLISTED filesystem failure is exactly the diagnostic an operator needs
    // for a failed credential write, so the split must not have made the branch uselessly silent.
    @Test
    fun `an allowlisted filesystem failure still reaches the persist line - DR-151`() {
        val denied = java.nio.file.FileSystemException(
            "/tmp/auth.json",
            null,
            "No space left",
        )
        val line = logsOf(RefreshOutcome.PersistFailed.Write(denied)).single()
        assertTrue(line.contains("No space left"), "a FileSystemException quotes paths we authored, not content: $line")
    }

    // The semantic branch has no throwable to carry, so it is an object and its text is fixed.
    @Test
    fun `the unreadable-after-write branch renders its exact literal - DR-151`() {
        val line = logsOf(RefreshOutcome.PersistFailed.UnreadableAfterWrite).single()
        assertTrue(
            line.contains("credential file unreadable after rotated-token write"),
            "the semantic branch keeps its own distinguishable story: $line",
        )
        assertFalse(line.contains("withheld"), "there is no throwable here, so nothing is withheld: $line")
    }
}
