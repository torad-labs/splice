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
import splice.app.LoginIo
import splice.app.auth.OAuthAccountFiles
import splice.app.auth.OAuthAccountLabels
import splice.app.auth.OAuthAccountRefused
import splice.app.cli.LoginCodex
import splice.app.cli.LoginGrok
import splice.app.cli.LoginKimi
import splice.core.topology.AuthKind
import splice.provider.kimi.KimiDeviceIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class AccountLoginTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the labeled login receipt says saved-for-restart, never using`() {
        val labeled = LoginIo().outcomeText("claudex", ok = true, label = "work")
        assertTrue(labeled.contains("signed in as 'work'") && labeled.contains("splice restart"), labeled)
        assertFalse(labeled.contains("using"), "the labeled account is not what this session uses: $labeled")
        assertTrue(LoginIo().outcomeText("claudex", ok = true, label = null).contains("using the new credentials"))
        assertTrue(LoginIo().outcomeText("claudex", ok = false, label = "work").contains("claudex login"))
    }

    @Test
    fun `first login keeps the provider native legacy primary`() {
        val primary = dir.resolve("grok.json")
        val account = OAuthAccountFiles().loginAccount(AuthKind.GrokOAuth, primary, requestedLabel = null)
        val providerJson = """{"tokens":{"access_token":"primary-secret"},"expires":123}"""

        assertTrue(LoginIo().persistIfSignedIn(primary, providerJson, account))

        assertTrue(account.primary)
        assertEquals(providerJson, Files.readString(primary))
        val onDisk = Json.parseToJsonElement(Files.readString(primary)).jsonObject
        assertFalse("splice_auth_kind" in onDisk)
        assertFalse("splice_account_label" in onDisk)
    }

    @Test
    fun `labeled login writes metadata beside the untouched primary`() {
        val primary = dir.resolve("kimi.json")
        val original = """{"access_token":"primary-secret"}"""
        Files.writeString(primary, original)
        val account = OAuthAccountFiles().loginAccount(AuthKind.KimiOAuth, primary, "work")
        val providerJson = """{"access_token":"backup-secret","refresh_token":"rotating"}"""

        assertTrue(LoginIo().persistIfSignedIn(primary, providerJson, account))

        assertEquals(original, Files.readString(primary))
        val target = dir.resolve("kimi-oauth/work.json")
        val onDisk = Json.parseToJsonElement(Files.readString(target)).jsonObject
        assertEquals("backup-secret", onDisk["access_token"]?.jsonPrimitive?.content)
        assertEquals("kimi-oauth", onDisk["splice_auth_kind"]?.jsonPrimitive?.content)
        assertEquals("work", onDisk["splice_account_label"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a labeled login cannot create a pool without its primary`() {
        val failure = assertThrows<OAuthAccountRefused> {
            OAuthAccountFiles().loginAccount(AuthKind.GrokOAuth, dir.resolve("grok.json"), "work")
        }

        assertTrue(failure.reason.contains("without --label first"))
    }

    @Test
    fun `a second unlabeled login rewrites the primary instead of stranding it`() {
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, """{"tokens":{"access_token":"revoked-primary"}}""")
        val account = OAuthAccountFiles().loginAccount(AuthKind.ChatgptOAuth, primary, requestedLabel = null)
        val replacement = """{"tokens":{"access_token":"replacement-primary"}}"""

        assertTrue(LoginIo().persistIfSignedIn(primary, replacement, account))

        assertTrue(account.primary)
        assertEquals(replacement, Files.readString(primary))
        assertFalse(Files.exists(dir.resolve("chatgpt-oauth")))
    }

    @Test
    fun `labels ending in the quota suffix are refused before login`() {
        val primary = dir.resolve("grok.json")
        Files.writeString(primary, "{}")

        val failure = assertThrows<OAuthAccountRefused> {
            OAuthAccountFiles().loginAccount(AuthKind.GrokOAuth, primary, "work-quota")
        }

        assertEquals("OAuth account labels must not end in -quota", failure.reason)
    }

    @Test
    fun `auto cannot be persisted as a literal account label`() {
        val primary = dir.resolve("grok.json")
        val store = OAuthAccountFiles()
        Files.writeString(primary, "{}")

        val failure = assertThrows<OAuthAccountRefused> {
            store.writeLabeled(AuthKind.GrokOAuth, primary, "auto", JsonObject(emptyMap()))
        }

        assertEquals("OAuth account label auto is reserved for derived labels", failure.reason)
        assertFalse(Files.exists(store.poolDir(AuthKind.GrokOAuth, primary).resolve("auto.json")))
    }

    @Test
    fun `retained quota cannot be inherited by a reused automatic or explicit label`() {
        val primary = dir.resolve("grok.json")
        val store = OAuthAccountFiles()
        val pool = store.poolDir(AuthKind.GrokOAuth, primary)
        Files.writeString(primary, "{}")
        Files.createDirectories(pool)
        Files.writeString(pool.resolve("grok-2-quota.json"), "{}")

        val automatic = requireNotNull(LoginGrok().spec("grok", primary, "auto").account)
        val explicit = assertThrows<OAuthAccountRefused> {
            store.loginAccount(AuthKind.GrokOAuth, primary, "grok-2")
        }

        assertEquals("grok-3", automatic.resolvedLabel())
        assertTrue(explicit.reason.contains("retained quota state"))
        assertFalse(explicit.reason.contains("grok-2"))
    }

    @Test
    fun `dangling quota reserves ordinals before login and preserves the kimi device identity`() {
        val store = OAuthAccountFiles()
        for (kind in listOf(AuthKind.GrokOAuth, AuthKind.KimiOAuth)) {
            val base = kind.wire.removeSuffix("-oauth")
            val primary = dir.resolve("$base.json")
            Files.writeString(primary, "{}")
            val pool = Files.createDirectories(store.poolDir(kind, primary))
            Files.createSymbolicLink(pool.resolve("$base-2-quota.json"), dir.resolve("$base-gone"))
            Files.createSymbolicLink(pool.resolve("$base-3.json"), primary)
            Files.createSymbolicLink(pool.resolve("$base-4.json"), dir.resolve("$base-gone-credential"))
            assertThrows<OAuthAccountRefused> { store.loginAccount(kind, primary, "$base-2") }
            val account = store.loginAccount(kind, primary, "auto")
            assertEquals("$base-5", account.resolvedLabel())
            assertFalse(account.tokenDerivedLabel)
        }
        val primary = dir.resolve("kimi.json")
        val spec = LoginKimi().spec("kimi", primary, "auto")
        val account = requireNotNull(spec.account)

        assertTrue(LoginIo().persistIfSignedIn(primary, """{"access_token":"kimi-secret"}""", account))

        val pool = store.poolDir(AuthKind.KimiOAuth, primary)
        val persistedIdentity = KimiDeviceIdentity(deviceIdPath = pool.resolve("kimi-5-device_id"))
        assertEquals(persistedIdentity.headers(), spec.identityHeaders)
        assertTrue(Files.exists(pool.resolve("kimi-5.json")))
        assertTrue(Files.isSymbolicLink(pool.resolve("kimi-2-quota.json")))
        assertTrue(Files.isSymbolicLink(pool.resolve("kimi-3.json")))
        assertTrue(Files.isSymbolicLink(pool.resolve("kimi-4.json")))
        assertEquals("{}", Files.readString(primary))
    }

    @Test
    fun `codex auto label derives plan and account hash after token shaping`() {
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, "{}")
        val account = requireNotNull(LoginCodex().spec("codex", primary, "auto").account)
        val claims = """
            {"https://api.openai.com/auth":{
              "chatgpt_account_id":"private-account-id","chatgpt_plan_type":"Plus"
            }}
        """.trimIndent()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
        val token = "header.$payload.signature"
        val authJson = """{"tokens":{"access_token":"$token","account_id":"private-account-id"}}"""
        val expected = OAuthAccountLabels.chatGpt("Plus", "private-account-id")

        assertTrue(LoginIo().persistIfSignedIn(primary, authJson, account))

        val pool = OAuthAccountFiles().poolDir(AuthKind.ChatgptOAuth, primary)
        assertTrue(Files.exists(pool.resolve("$expected.json")))
        assertFalse(Files.exists(pool.resolve("auto.json")))
        assertFalse(expected.contains("private-account-id"))
    }

    @Test
    fun `grok and kimi auto labels use the next provider ordinal`() {
        val grokPrimary = dir.resolve("grok.json")
        Files.writeString(grokPrimary, "{}")
        val grok = requireNotNull(LoginGrok().spec("grok", grokPrimary, "auto").account)
        assertTrue(
            LoginIo().persistIfSignedIn(
                grokPrimary,
                """{"tokens":{"access_token":"grok-secret"}}""",
                grok,
            ),
        )
        assertTrue(Files.exists(dir.resolve("grok-oauth/grok-2.json")))

        val kimiPrimary = dir.resolve("kimi.json")
        Files.writeString(kimiPrimary, "{}")
        val store = OAuthAccountFiles()
        store.writeLabeled(
            AuthKind.KimiOAuth,
            kimiPrimary,
            "kimi-2",
            JsonObject(mapOf("access_token" to JsonPrimitive("old-secret"))),
        )
        val kimi = requireNotNull(LoginKimi().spec("kimi", kimiPrimary, "auto").account)
        assertEquals("kimi-3", kimi.resolvedLabel())
        assertTrue(LoginIo().persistIfSignedIn(kimiPrimary, """{"access_token":"kimi-secret"}""", kimi))
        assertTrue(Files.exists(dir.resolve("kimi-oauth/kimi-3.json")))
        assertFalse(Files.exists(dir.resolve("kimi-oauth/auto.json")))
    }
}
