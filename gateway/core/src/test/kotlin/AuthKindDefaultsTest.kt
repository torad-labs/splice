// NEW: 2026-09-05 — splice owns its credential: every OAuth kind's default file is splice's own
// under ~/.config/splice/auth/, never the native app's, and the legacy knobs agree with it.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.Knob
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry

class AuthKindDefaultsTest {

    private val nativeAppFiles =
        listOf("~/.codex/auth.json", "~/.grok/auth.json", "~/.kimi/credentials/kimi-code.json")

    @Test
    fun `every OAuth kind defaults to a splice-owned credential file, never the native app's`() {
        val oauth = AuthKindRegistry.knownKinds().filterIsInstance<AuthKind.OAuth>()
        assertEquals(3, oauth.size, "the registry's OAuth kinds")
        oauth.forEach { kind ->
            assertTrue(kind.authFile.startsWith("~/.config/splice/auth/"), "${kind.wire}: ${kind.authFile}")
            assertFalse(kind.authFile in nativeAppFiles, "${kind.wire} must not share the native app's file")
            assertEquals(kind.authFile, AuthKindRegistry.defaultAuthFileFor(kind.wire))
            // The registry knows the app's file only so doctor can name a config that still shares it.
            assertTrue(kind.nativeAppFile in nativeAppFiles, "${kind.wire}: ${kind.nativeAppFile}")
            assertFalse(kind.nativeAppFile == kind.authFile)
        }
        assertEquals(oauth.size, oauth.map { it.authFile }.toSet().size, "one file per kind")
    }

    @Test
    fun `the legacy codex and grok knobs default to the registry's splice-owned files`() {
        assertEquals(AuthKind.ChatgptOAuth.authFile, Knob.CODEX_AUTH_PATH.default)
        assertEquals(AuthKind.GrokOAuth.authFile, Knob.GROK_AUTH_PATH.default)
    }
}
