// NEW: re-review of adf35c39e, finding 4. A native command's quota was taken only from readings that named a
// window, so an authoritative empty answer (the provider's usage endpoint saying this account has no usage,
// 7d5db57a6) was dropped and the stale bars stayed current until their freshness ran out.
package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.LogSink
import splice.head.usage.CredentialQuotaFiles
import java.nio.file.Files
import java.nio.file.Path

class ClaudeNativeQuotaTest {
    @TempDir
    lateinit var directory: Path

    private fun quota(): Pair<ClaudeNativeQuota, String> {
        Files.writeString(
            directory.resolve(CREDENTIALS_JSON),
            """{"claudeAiOauth":{"accessToken":"native-token","expiresAt":9999999999999}}""",
        )
        val auth = ClaudeNativeAuth(
            directory,
            ClaudeLoginPlaceId.entries.first(),
            ClaudeCredentialProfiles(directory.resolve("profiles"), LogSink {}),
            LogSink {},
        )
        val files = CredentialQuotaFiles(directory.resolve("native-quota.json"), LogSink {})
        return ClaudeNativeQuota(auth, files) to requireNotNull(auth.credentialKey)
    }

    private val standing = QuotaSnapshot(
        fiveHour = QuotaWindow(40.0, 20_000L, 18_000L),
        sevenDay = QuotaWindow(50.0, 600_000L, 604_800L),
        updatedAt = 1_000L,
    )

    @Test
    fun `an empty answer from the provider replaces the standing reading instead of leaving it current`() {
        val (native, key) = quota()
        native.observed(key, standing)
        assertEquals(standing, native.snapshot())

        native.observed(key, QuotaSnapshot(updatedAt = 2_000L))

        val now = requireNotNull(native.snapshot())
        assertTrue(now.answeredEmpty, "the provider answered with no usage: $now")
        assertEquals(2_000L, now.updatedAt)
    }

    @Test
    fun `an empty reading that carries no time is still no answer, and the standing reading stays`() {
        val (native, key) = quota()
        native.observed(key, standing)

        native.observed(key, QuotaSnapshot())

        assertEquals(standing, native.snapshot())
    }
}
