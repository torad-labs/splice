// NEW (discipline L3): pins for the sealed refresh outcome and its single SPI flatten —
// Refreshed passes credentials through silently; every failure branch logs EXACTLY one line
// that names its distinct story (dead token vs transport blip vs corrupt file vs not-logged-in
// were previously one indistinguishable null — the 2026-07-18 incident shape).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.auth.RefreshOutcome
import splice.core.auth.credentialsOrNull
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
            RefreshOutcome.PersistFailed("disk full") to "persist failed",
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
            RefreshOutcome.PersistFailed("p"),
        ).map { logsOf(it).second.single() }
        assertEquals(all.size, all.toSet().size)
    }
}
