// NEW: muse twins of the kimi reservation cases in AccountLoginTest.
package splice.app.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.auth.LoginMuse
import splice.core.topology.AuthKind
import java.nio.file.Files
import java.nio.file.Path

class AccountLoginMuseTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `labeled muse login writes metadata beside the untouched primary`() {
        val primary = dir.resolve("muse.json")
        val original = """{"access_token":"primary-secret"}"""
        Files.writeString(primary, original)
        val account = OAuthAccountFiles().loginAccount(AuthKind.MuseOAuth, primary, "work")
        assertTrue(LoginIo().persistIfSignedIn(primary, """{"access_token":"backup-secret"}""", account))
        assertEquals(original, Files.readString(primary))
        val target = dir.resolve("muse-oauth/muse.json/work.json")
        val onDisk = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(target)).jsonObject
        assertEquals("backup-secret", onDisk["access_token"]?.jsonPrimitive?.content)
        assertEquals("muse-oauth", onDisk["splice_auth_kind"]?.jsonPrimitive?.content)
        assertEquals("work", onDisk["splice_account_label"]?.jsonPrimitive?.content)
    }

    @Test
    fun `muse explicit labels lease the same namespace as automatic ordinals`() {
        val primary = dir.resolve("muse.json")
        Files.writeString(primary, "{}")
        val explicit = LoginMuse().spec("claude-muse", primary, "muse-2")
        try {
            val automatic = LoginMuse().spec("claude-muse", primary, "auto")
            try {
                assertEquals("muse-2", requireNotNull(explicit.account).resolvedLabel())
                assertEquals("muse-3", requireNotNull(automatic.account).resolvedLabel())
            } finally {
                automatic.account?.releaseReservation()
            }
        } finally {
            explicit.account?.releaseReservation()
        }
    }

    @Test
    fun `muse explicit relogin replaces its credential while refusing a simultaneous owner`() {
        val primary = dir.resolve("muse.json")
        Files.writeString(primary, "{}")
        val store = OAuthAccountFiles()
        store.writeLabeled(
            AuthKind.MuseOAuth,
            primary,
            "work",
            JsonObject(mapOf("access_token" to JsonPrimitive("old-secret"))),
        )
        val first = LoginMuse().spec("claude-muse", primary, "work")
        try {
            val refused = assertThrows<OAuthAccountRefused> {
                LoginMuse().spec("claude-muse", primary, "work")
            }
            assertEquals("OAuth account label already has a login in progress", refused.reason)
            assertTrue(
                LoginIo().persistIfSignedIn(
                    primary,
                    """{"access_token":"replacement-secret"}""",
                    requireNotNull(first.account),
                ),
            )
            val saved = Json.parseToJsonElement(
                Files.readString(store.poolDir(AuthKind.MuseOAuth, primary).resolve("work.json")),
            ).jsonObject
            assertEquals("replacement-secret", saved["access_token"]?.jsonPrimitive?.content)
            assertFalse(saved["access_token"]?.jsonPrimitive?.content == "old-secret")
        } finally {
            first.account?.releaseReservation()
        }
        assertFalse(Files.readString(primary).contains("replacement-secret"))
    }
}
