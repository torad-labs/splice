// native account facts and credential joins come from independent files, never the head aggregate.
package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
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

    private fun profiles(): ClaudeCredentialProfiles = ClaudeCredentialProfiles(home.resolve("profile-state"), {})

    private fun reader(): ClaudeLoginFactsReader = ClaudeLoginFactsReader(profiles())

    @Test
    fun `the provider profile identifies the bearer rather than either copied account record`() {
        val place = location()
        Files.writeString(place.credentials, """{"claudeAiOauth":{"accessToken":"synthetic-token","ignored":true}}""")
        val right = """{"oauthAccount":{"accountUuid":"right","emailAddress":"right@test"}}"""
        val wrong = """{"oauthAccount":{"accountUuid":"wrong"}}"""
        Files.writeString(place.target.accountFile, right)
        Files.writeString(place.target.head.configDir.resolve(".claude.json"), wrong)
        val digest = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-token")))
        profiles().observed(digest, ClaudeAccountIdentity("proved", "proved@synthetic.test"))
        val facts = reader().read(place)
        assertTrue(facts.present)
        assertEquals("proved", facts.account?.uuid)
        assertEquals("proved@synthetic.test", facts.account?.email)
        val expected = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-token"))
        assertEquals(expected, facts.key)
        assertNull(facts.refusal)
    }

    @Test
    fun `a missing account record does not erase a proven credentials quota join`() {
        val place = location()
        Files.writeString(place.credentials, """{"claudeAiOauth":{"accessToken":"synthetic-token"}}""")
        val facts = reader().read(place)
        assertTrue(facts.present)
        assertNull(facts.account)
        assertTrue(facts.key != null)
    }

    @Test
    fun `credential absence never claims the account named by an unbound settings record`() {
        val place = location()
        Files.writeString(place.target.accountFile, """{"oauthAccount":{"accountUuid":"known"}}""")
        val facts = reader().read(place)
        assertFalse(facts.present)
        assertNull(facts.account)
        assertNull(facts.key)
    }

    @Test
    fun `corrupt credentials remain present and unknown without exposing their bytes`() {
        val place = location()
        Files.writeString(place.credentials, "private-synthetic-invalid-json")
        Files.writeString(place.target.accountFile, """{"oauthAccount":{"accountUuid":"known"}}""")
        val facts = reader().read(place)
        assertTrue(facts.present)
        assertNull(facts.account)
        assertNull(facts.key)
        assertTrue(facts.refusal != null)
        assertFalse(facts.refusal.orEmpty().contains("private-synthetic"))
    }
}
