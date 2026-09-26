// NEW: V4-152 — split out of GrokAuthProviderTest.kt, which held SEVEN classes in one 830-line file
// and tripped detekt's LargeClass ceiling at 400. This class is NOT new: it already existed and
// already had its own JUnit report, so only its FILE was wrong. Body byte-for-byte; the split's
// acceptance is that :providers-grok:test reports the same total before and after.
package splice.provider.grok

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.auth.RefreshAttempt
import java.nio.file.Files

class GrokAccountMetadataTest {
    @Test
    fun `refresh preserves labeled metadata and never decorates the legacy primary`() = runTest {
        for (labeled in listOf(false, true)) {
            val dir = Files.createTempDirectory("grok-account-metadata")
            val file = dir.resolve("auth.json")
            val metadata = if (labeled) {
                ""","splice_auth_kind":"grok-oauth","splice_account_label":"backup""""
            } else {
                ""
            }
            Files.writeString(
                file,
                """{"tokens":{"access_token":"old","refresh_token":"refresh"},
                    "expires":1$metadata}""",
            )
            val auth = GrokAuthProvider(
                authPath = file,
                authCacheMs = 30_000L,
                clock = { 1_000_000L },
                refreshCall = {
                    RefreshAttempt.Granted(GrokRefreshedTokens("new", "rotated", expiresIn = 3600))
                },
            )

            auth.refresh()

            val onDisk = Json.parseToJsonElement(Files.readString(file)).jsonObject
            if (labeled) {
                assertEquals("grok-oauth", onDisk["splice_auth_kind"]?.jsonPrimitive?.content)
                assertEquals("backup", onDisk["splice_account_label"]?.jsonPrimitive?.content)
                assertFalse("splice_auth_kind" in onDisk["tokens"]!!.jsonObject)
                assertFalse("splice_account_label" in onDisk["tokens"]!!.jsonObject)
            } else {
                assertFalse("splice_auth_kind" in onDisk)
                assertFalse("splice_account_label" in onDisk)
            }
        }
    }
}
