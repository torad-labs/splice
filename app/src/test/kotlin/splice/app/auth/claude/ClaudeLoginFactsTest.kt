// NEW: native account facts and credential joins come from independent files, never the head aggregate.
package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.core.auth.CredentialKey
import java.nio.file.Files
import java.nio.file.Path

class ClaudeLoginFactsTest {
    @TempDir
    lateinit var home: Path

    private fun location(): ClaudeLoginLocation {
        val config = Files.createDirectories(home.resolve(".claude"))
        return ClaudeLoginLocation(
            ClaudeLoginPlaceId.NATIVE,
            ClaudeLoginTarget(ClaudeHead("claude-splice", config), home.resolve(".claude.json")),
            home.resolve("safe-copies"),
        )
    }

    @Test
    fun `default account identity and effective bearer join do not use the directory-local account`() {
        val place = location()
        Files.writeString(place.credentials, """{"claudeAiOauth":{"accessToken":"synthetic-token","ignored":true}}""")
        val right = """{"oauthAccount":{"accountUuid":"right","emailAddress":"right@test"}}"""
        val wrong = """{"oauthAccount":{"accountUuid":"wrong"}}"""
        Files.writeString(place.target.accountFile, right)
        Files.writeString(place.target.head.configDir.resolve(".claude.json"), wrong)
        val facts = ClaudeLoginFactsReader().read(place)
        assertTrue(facts.present)
        assertEquals("right", facts.account?.uuid)
        assertEquals("right@test", facts.account?.email)
        val expected = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-token"))
        assertEquals(expected, facts.key)
        assertNull(facts.refusal)
    }

    @Test
    fun `a missing account record does not erase a proven credentials quota join`() {
        val place = location()
        Files.writeString(place.credentials, """{"claudeAiOauth":{"accessToken":"synthetic-token"}}""")
        val facts = ClaudeLoginFactsReader().read(place)
        assertTrue(facts.present)
        assertNull(facts.account)
        assertTrue(facts.key != null)
    }

    @Test
    fun `credential absence preserves the native account record without claiming a quota join`() {
        val place = location()
        Files.writeString(place.target.accountFile, """{"oauthAccount":{"accountUuid":"known"}}""")
        val facts = ClaudeLoginFactsReader().read(place)
        assertFalse(facts.present)
        assertEquals("known", facts.account?.uuid)
        assertNull(facts.key)
    }

    @Test
    fun `corrupt credentials remain present and unknown without exposing their bytes`() {
        val place = location()
        Files.writeString(place.credentials, "private-synthetic-invalid-json")
        Files.writeString(place.target.accountFile, """{"oauthAccount":{"accountUuid":"known"}}""")
        val facts = ClaudeLoginFactsReader().read(place)
        assertTrue(facts.present)
        assertEquals("known", facts.account?.uuid)
        assertNull(facts.key)
        assertTrue(facts.refusal != null)
        assertFalse(facts.refusal.orEmpty().contains("private-synthetic"))
    }
}
