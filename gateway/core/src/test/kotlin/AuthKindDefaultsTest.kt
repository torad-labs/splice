// NEW: 2026-09-05 — splice owns its credential: every OAuth kind's default file is splice's own
// under ~/.config/splice/auth/, never the native app's, and the legacy knobs agree with it.
import kotlinx.serialization.SerialName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.Knob
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.DialectWires
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig

class AuthKindDefaultsTest {

    private val nativeAppFiles = listOf(
        "~/.codex/auth.json",
        "~/.grok/auth.json",
        "~/.kimi/credentials/kimi-code.json",
        "~/.config/muse/auth.json",
    )

    @Test
    fun `every OAuth kind defaults to a splice-owned credential file, never the native app's`() {
        val oauth = AuthKindRegistry.knownKinds().filterIsInstance<AuthKind.OAuth>()
        assertEquals(4, oauth.size, "the registry's OAuth kinds")
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
    fun `Muse registers its own credential and records the native file only for doctor`() {
        val kind = AuthKindRegistry.from("muse-oauth")
        assertTrue(kind is AuthKind.OAuth, "muse-oauth must be registered, not only match the OAuth suffix")
        val oauth = kind as AuthKind.OAuth
        assertEquals("muse-oauth", oauth.wire)
        assertEquals("~/.config/splice/auth/muse.json", oauth.authFile)
        assertEquals("~/.config/splice/auth/muse.json", AuthKindRegistry.defaultAuthFileFor(oauth.wire))
        assertEquals("~/.config/muse/auth.json", oauth.nativeAppFile)
        assertEquals("Muse Code", oauth.nativeApp)
        assertTrue(oauth.isOAuth)
    }

    @Test
    fun `the legacy codex and grok knobs default to the registry's splice-owned files`() {
        assertEquals(AuthKind.ChatgptOAuth.authFile, Knob.CODEX_AUTH_PATH.default)
        assertEquals(AuthKind.GrokOAuth.authFile, Knob.GROK_AUTH_PATH.default)
    }

    @Test
    fun `code mode is a registry capability walked against every kind and dialect`() {
        val claimants = AuthKindRegistry.knownKinds().filter { it.codeModeDialect != null }
        assertEquals(1, claimants.size)
        assertEquals(AuthKind.ChatgptOAuth, claimants.single())
        assertEquals(Dialect.OPENAI_RESPONSES, AuthKind.ChatgptOAuth.codeModeDialect)
        AuthKindRegistry.knownKinds().forEach { kind ->
            Dialect.entries.forEach { dialect ->
                val cfg = ProviderConfig(
                    dialect = dialect,
                    baseUrl = "https://example.invalid",
                    auth = AuthConfig(kind.wire),
                )
                val expected = kind.codeModeDialect == dialect
                assertEquals(expected, cfg.codeModeEnabled, "${kind.wire} x $dialect")
                val off = cfg.copy(quirks = QuirksConfig(codeMode = false))
                assertEquals(false, off.codeModeEnabled, "${kind.wire} x $dialect explicit off")
                if (expected) {
                    val on = cfg.copy(quirks = QuirksConfig(codeMode = true))
                    assertEquals(true, on.codeModeEnabled, "${kind.wire} x $dialect explicit on")
                } else {
                    val error = assertThrows(IllegalArgumentException::class.java) {
                        cfg.copy(quirks = QuirksConfig(codeMode = true))
                    }
                    assertEquals(
                        "code_mode is only supported with auth.kind = 'chatgpt-oauth' " +
                            "and dialect = 'openai-responses'",
                        error.message,
                        "${kind.wire} x $dialect",
                    )
                }
            }
        }
    }

    @Test
    fun `every Dialect wire spelling equals the SerialName on the enum`() {
        val descriptor = Dialect.serializer().descriptor
        Dialect.entries.forEach { dialect ->
            val serial = Dialect::class.java.getField(dialect.name)
                .getAnnotation(SerialName::class.java)?.value
            assertEquals(serial, descriptor.getElementName(dialect.ordinal), dialect.name)
            assertEquals(serial, DialectWires.name(dialect), dialect.name)
        }
    }
}
