// NEW: current credential joins cannot read either the head aggregate or another native command's observations.
package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
    private fun place(id: ClaudeLoginPlaceId, token: String, account: String? = null): ClaudeLoginLocation {
        val folder = Files.createDirectories(home.resolve(id.wire))
        val record = folder.resolve(".claude.json")
        val uuid = account?.let { """"accountUuid":"$it"""" } ?: ""
        Files.writeString(folder.resolve(".credentials.json"), """{"claudeAiOauth":{"accessToken":"$token"}}""")
        Files.writeString(record, """{"oauthAccount":{$uuid}}""")
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
    fun `native commands have distinct standing and credential rotation never falls back to old or head-wide quota`() {
        val paths = paths()
        val first = place(ClaudeLoginPlaceId.NATIVE, "first-synthetic", account = "first-account")
        val second = place(ClaudeLoginPlaceId.SPLICE, "second-synthetic", account = "second-account")
        Files.writeString(paths.quotaFile("claude-splice"), QuotaJson().encode(window(99.0, 100_000)))
        assertNull(used(read(first, second), ClaudeLoginPlaceId.NATIVE))
        assertNull(used(read(first, second), ClaudeLoginPlaceId.SPLICE))
        observations(paths).observed(key("first-synthetic"), window(11.0, 100_000))
        observations(paths).observed(key("second-synthetic"), window(77.0, 100_000))
        assertEquals(11.0, used(read(first, second), ClaudeLoginPlaceId.NATIVE))
        assertEquals(77.0, used(read(first, second), ClaudeLoginPlaceId.SPLICE))
        assertEquals("first-account", read(first, second).getValue(ClaudeLoginPlaceId.NATIVE).account?.uuid)
        Files.writeString(first.credentials, """{"claudeAiOauth":{"accessToken":"rotated-synthetic"}}""")
        assertNull(used(read(first, second), ClaudeLoginPlaceId.NATIVE), "a rotated token inherits no window")
        assertTrue(read(first, second).getValue(ClaudeLoginPlaceId.NATIVE).credentialPresent)
        assertEquals(77.0, used(read(first, second), ClaudeLoginPlaceId.SPLICE))
    }

    @Test
    fun `two logins of one account read that account's newest window rather than splitting it`() {
        val paths = paths()
        val native = place(ClaudeLoginPlaceId.NATIVE, "native-synthetic", account = "one-subscription")
        val added = place(ClaudeLoginPlaceId.SPLICE, "splice-synthetic", account = "one-subscription")
        observations(paths).observed(key("native-synthetic"), window(34.0, 90_000))

        val probed = read(native, added)

        assertEquals(34.0, used(probed, ClaudeLoginPlaceId.NATIVE))
        assertEquals(
            34.0,
            used(probed, ClaudeLoginPlaceId.SPLICE),
            "one subscription spends one window, so the login that has observed none reads its account's",
        )

        observations(paths).observed(key("splice-synthetic"), window(41.0, 95_000))
        val later = read(native, added)

        assertEquals(41.0, used(later, ClaudeLoginPlaceId.NATIVE), "the account's newest reading, whoever filed it")
        assertEquals(41.0, used(later, ClaudeLoginPlaceId.SPLICE))
    }

    @Test
    fun `a login whose account splice cannot read joins with nobody`() {
        val paths = paths()
        val named = place(ClaudeLoginPlaceId.NATIVE, "named-synthetic", account = "one-subscription")
        val anonymous = place(ClaudeLoginPlaceId.SPLICE, "anonymous-synthetic", account = null)
        observations(paths).observed(key("named-synthetic"), window(52.0, 90_000))

        val places = read(named, anonymous)

        assertEquals(52.0, used(places, ClaudeLoginPlaceId.NATIVE))
        assertNull(
            used(places, ClaudeLoginPlaceId.SPLICE),
            "no identity means nothing to join by, and a guess would show another person's window",
        )

        observations(paths).observed(key("anonymous-synthetic"), window(63.0, 95_000))
        val own = read(named, anonymous)

        assertEquals(63.0, used(own, ClaudeLoginPlaceId.SPLICE), "it still reads its own credential's observation")
        assertEquals(52.0, used(own, ClaudeLoginPlaceId.NATIVE), "and lends nothing to an account it is not in")
    }

    private fun hold(token: String, reset: Long) {
        ProviderHoldFiles(paths(), {}).forHead("claude-splice").forCredential(key(token))!!
            .save(ProviderHold(reset, PlanLimit("5-hour", reset)))
    }

    @Test
    fun `one account held through a native place holds its other place's different token too`() {
        val native = place(ClaudeLoginPlaceId.NATIVE, "native-held-synthetic", account = "one-subscription")
        val added = place(ClaudeLoginPlaceId.SPLICE, "splice-free-synthetic", account = "one-subscription")
        hold("native-held-synthetic", 18_000L)

        val shared = read(native, added)

        assertEquals(true, shared.getValue(ClaudeLoginPlaceId.NATIVE).standing.held)
        assertEquals(true, shared.getValue(ClaudeLoginPlaceId.SPLICE).standing.held)
        assertEquals(18_000L, shared.getValue(ClaudeLoginPlaceId.SPLICE).standing.untilEpochSeconds)

        hold("splice-free-synthetic", 25_000L)
        val later = read(native, added)
        assertEquals(25_000L, later.getValue(ClaudeLoginPlaceId.NATIVE).standing.untilEpochSeconds)
        assertEquals(25_000L, later.getValue(ClaudeLoginPlaceId.SPLICE).standing.untilEpochSeconds)

        Files.writeString(native.credentials, """{"claudeAiOauth":{"accessToken":"rotated-synthetic"}}""")
        Files.writeString(added.credentials, """{"claudeAiOauth":{"accessToken":"other-rotated-synthetic"}}""")
        val rotated = read(native, added)
        assertNull(rotated.getValue(ClaudeLoginPlaceId.NATIVE).standing.held, "retired observers lend no hold")
        assertNull(rotated.getValue(ClaudeLoginPlaceId.SPLICE).standing.held)
    }

    @Test
    fun `an account's hold is not lent to a different account or an unknown identity`() {
        val held = place(ClaudeLoginPlaceId.NATIVE, "held-synthetic", account = "held-account")
        val other = place(ClaudeLoginPlaceId.SPLICE, "other-synthetic", account = "other-account")
        hold("held-synthetic", 18_000L)
        assertNull(read(held, other).getValue(ClaudeLoginPlaceId.SPLICE).standing.held)

        val anonymous = place(ClaudeLoginPlaceId.SPLICE, "anonymous-synthetic")
        assertNull(read(held, anonymous).getValue(ClaudeLoginPlaceId.SPLICE).standing.held)
        hold("anonymous-synthetic", 22_000L)
        val separate = read(held, anonymous)
        assertEquals(18_000L, separate.getValue(ClaudeLoginPlaceId.NATIVE).standing.untilEpochSeconds)
        assertEquals(22_000L, separate.getValue(ClaudeLoginPlaceId.SPLICE).standing.untilEpochSeconds)
    }
}
