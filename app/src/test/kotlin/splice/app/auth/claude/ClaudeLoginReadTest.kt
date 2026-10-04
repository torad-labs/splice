// NEW: current credential joins cannot read either the head aggregate or another native command's observations.
package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.core.auth.CredentialKey
import splice.core.config.StatePaths
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
import splice.head.usage.CredentialQuotaFiles
import java.nio.file.Files
import java.nio.file.Path

class ClaudeLoginReadTest {
    @TempDir
    lateinit var home: Path

    private fun place(id: ClaudeLoginPlaceId, token: String): ClaudeLoginLocation {
        val folder = Files.createDirectories(home.resolve(id.wire))
        val account = folder.resolve(".claude.json")
        Files.writeString(folder.resolve(".credentials.json"), """{"claudeAiOauth":{"accessToken":"$token"}}""")
        Files.writeString(account, """{"oauthAccount":{"accountUuid":"${id.wire}"}}""")
        return ClaudeLoginLocation(id, ClaudeLoginTarget(ClaudeHead("claude-splice", folder), account), home)
    }

    @Test
    fun `native commands have distinct standing and credential rotation never falls back to old or head-wide quota`() {
        val paths = StatePaths(baseOverride = home.resolve("state"))
        Files.createDirectories(paths.stateDir)
        val first = place(ClaudeLoginPlaceId.NATIVE, "first-synthetic")
        val second = place(ClaudeLoginPlaceId.SPLICE, "second-synthetic")
        val aggregate = QuotaSnapshot(QuotaWindow(99.0, 10_000, 18_000), updatedAt = 100_000)
        Files.writeString(paths.quotaFile("claude-splice"), QuotaJson().encode(aggregate))
        val reader = ClaudeLoginRead(paths, {}, WallClock { 100_000 })
        assertNull(reader.read(first).quota)
        assertNull(reader.read(second).quota)
        val observations = CredentialQuotaFiles(paths.quotaFile("claude-splice"), {})
        val firstKey = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer first-synthetic"))!!
        val secondKey = CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer second-synthetic"))!!
        observations.observed(firstKey, aggregate.copy(fiveHour = QuotaWindow(11.0, 10_000, 18_000)))
        observations.observed(secondKey, aggregate.copy(fiveHour = QuotaWindow(77.0, 10_000, 18_000)))
        assertEquals(11.0, reader.read(first).quota?.fiveHour?.usedPercent)
        assertEquals(77.0, reader.read(second).quota?.fiveHour?.usedPercent)
        assertEquals("claude", reader.read(first).account?.uuid)
        Files.writeString(first.credentials, """{"claudeAiOauth":{"accessToken":"rotated-synthetic"}}""")
        assertNull(reader.read(first).quota)
        assertTrue(reader.read(first).credentialPresent)
        assertEquals(77.0, reader.read(second).quota?.fiveHour?.usedPercent)
    }
}
