// NEW: the codex auth cache holds a live access token; its generated toString printed it.
package splice.provider.codex

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexSnapshotRedactionTest {
    @Test
    fun `the cached access token never reaches toString, and the account does`() {
        val rendered = CodexAuthJson.Snapshot(
            access = SECRET,
            accountId = "acct-7",
            expiresAtMs = 42L,
        ).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("accountId=acct-7"), rendered)
        assertTrue(rendered.contains("expiresAtMs=42"), rendered)
    }

    private companion object {
        const val SECRET = "sk-live-REDACTION-CANARY-8f3a91c6"
    }
}
