// current credential joins cannot read either the head aggregate or another native command's observations.
package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.app.head.ProviderHoldFiles
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.core.auth.CredentialKey
import splice.core.config.StatePaths
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
import splice.head.usage.CredentialQuotaFiles
import splice.upstream.retry.ProviderHold
import java.nio.file.Files
import java.nio.file.Path

class ClaudeLoginReadTest {
    @TempDir
    lateinit var home: Path

    /** [account] is the subscription the folder's record names: the two places hold ONE account when it is equal,
     *  which is what a person signing the same login into both commands has, and null when splice cannot read one. */
    private fun place(
        id: ClaudeLoginPlaceId,
        token: String,
        account: String? = null,
        verified: Boolean = true,
    ): ClaudeLoginLocation {
        val folder = Files.createDirectories(home.resolve(id.wire))
        val record = folder.resolve(".claude.json")
        val uuid = account?.let { """"accountUuid":"$it"""" } ?: ""
        Files.writeString(folder.resolve(".credentials.json"), """{"claudeAiOauth":{"accessToken":"$token"}}""")
        Files.writeString(record, """{"oauthAccount":{$uuid}}""")
        if (account != null && verified) {
            ClaudeCredentialProfiles(paths().stateDir, {}).observed(key(token), ClaudeAccountIdentity(account, null))
        }
        return ClaudeLoginLocation(id, ClaudeLoginTarget(ClaudeHead("claude-splice", folder), record), home)
    }

    private fun paths(): StatePaths =
        StatePaths(baseOverride = home.resolve("state")).also { Files.createDirectories(it.stateDir) }

    private fun observations(paths: StatePaths): CredentialQuotaFiles =
        CredentialQuotaFiles(paths.quotaFile("claude-splice"), {})

    private fun key(token: String): String =
        CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token"))!!

    private fun window(usedPercent: Double, at: Long): QuotaSnapshot =
        QuotaSnapshot(QuotaWindow(usedPercent, 10_000, 18_000), updatedAt = at)

    private fun used(places: Map<ClaudeLoginPlaceId, ClaudeLoginPlaceView>, id: ClaudeLoginPlaceId): Double? =
        places.getValue(id).quota?.fiveHour?.usedPercent

    private fun read(vararg locations: ClaudeLoginLocation): Map<ClaudeLoginPlaceId, ClaudeLoginPlaceView> {
        val reader = ClaudeLoginRead(paths(), {}, WallClock { 100_000 })
        return reader.places(locations.toList()).associateBy { it.id }
    }

    @Test
    fun `account attribution uses verified matching credentials rather than conflicting copied identities`() {
        val first = place(ClaudeLoginPlaceId.NATIVE, "first-synthetic", account = "first-account")
        val reader = ClaudeLoginRead(paths(), {}, WallClock { 100_000 })
        val locations = listOf(first)
        assertEquals("first-account", reader.accountForCredential(locations, key("first-synthetic"))?.uuid)
        assertNull(reader.accountForCredential(locations, key("unproved-synthetic")))
        Files.writeString(first.credentials, """{"claudeAiOauth":{"accessToken":"rotated-synthetic"}}""")
        assertNull(reader.accountForCredential(locations, key("first-synthetic")), "a stale token proves no account")
        assertNull(reader.accountForCredential(locations, key("rotated-synthetic")), "a new credential is unverified")
        ClaudeCredentialProfiles(paths().stateDir, {}).observed(
            key("rotated-synthetic"),
            ClaudeAccountIdentity("first-account", null),
        )
        assertEquals("first-account", reader.accountForCredential(locations, key("rotated-synthetic"))?.uuid)
    }

    @Test
    fun `credential rotation never falls back to old or head-wide quota`() {
        val paths = paths()
        val first = place(ClaudeLoginPlaceId.NATIVE, "first-synthetic", account = "first-account")
        Files.writeString(paths.quotaFile("claude-splice"), QuotaJson().encode(window(99.0, 100_000)))
        assertNull(used(read(first), ClaudeLoginPlaceId.NATIVE))
        observations(paths).observed(key("first-synthetic"), window(11.0, 100_000))
        assertEquals(11.0, used(read(first), ClaudeLoginPlaceId.NATIVE))
        assertEquals("first-account", read(first).getValue(ClaudeLoginPlaceId.NATIVE).identity.account?.uuid)
        Files.writeString(first.credentials, """{"claudeAiOauth":{"accessToken":"rotated-synthetic"}}""")
        assertNull(used(read(first), ClaudeLoginPlaceId.NATIVE), "a rotated token inherits no window")
        assertTrue(read(first).getValue(ClaudeLoginPlaceId.NATIVE).credential.present)
    }

    private fun hold(token: String, reset: Long) {
        ProviderHoldFiles(paths(), {}).forHead("claude-splice").forCredential(key(token))!!
            .save(ProviderHold(reset, PlanLimit("5-hour", reset)))
    }

    @Test
    fun `a held login reads as held until its reset, and a rotated token lends no hold`() {
        val native = place(ClaudeLoginPlaceId.NATIVE, "native-held-synthetic", account = "one-subscription")
        hold("native-held-synthetic", 18_000L)

        val held = read(native)

        assertEquals(true, held.getValue(ClaudeLoginPlaceId.NATIVE).standing.held)
        assertEquals(18_000L, held.getValue(ClaudeLoginPlaceId.NATIVE).standing.untilEpochSeconds)

        Files.writeString(native.credentials, """{"claudeAiOauth":{"accessToken":"rotated-synthetic"}}""")
        assertNull(read(native).getValue(ClaudeLoginPlaceId.NATIVE).standing.held, "retired observers lend no hold")
    }
}
