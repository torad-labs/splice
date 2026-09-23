// DR-172 gap (found 2026-09-01 by the verifier, confirmed against the code): persistIfSignedIn read
// access_token at the TOP LEVEL only, while LoginCodex and LoginGrok hand it the ON-DISK shape their
// providers read back — the token nested under "tokens" (CodexAuthJson / GrokAuthJson FIELD_TOKENS).
// So every successful codex and grok exchange was refused as "no access token — NOT signed in".
// DeviceLoginTokenlessTest's control arm used a synthetic FLAT body, which is why the gate stayed
// green over the outage. These arms drive the REAL provider builders through the same call both
// login flows make, so the shape can never drift out from under the check again.
package splice.app.auth

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.auth.LoginCodex
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKind
import splice.provider.codex.CodexOAuth
import splice.provider.grok.GrokOAuth
import splice.provider.kimi.KimiOAuth
import splice.upstream.credentials.AccountLabelPolicy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

class LoginPersistShapeTest {

    private fun persist(path: Path, authJson: String): Pair<Boolean, String> {
        val out = StringBuilder()
        return LoginIo(TerminalOutput { out.appendLine(it) }).persistIfSignedIn(path, authJson) to out.toString()
    }

    @Test
    fun `the codex on-disk shape, token under tokens, is a sign-in - DR-172`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val json = CodexOAuth().authJsonFromTokens(
            idToken = null,
            accessToken = "tok_codex",
            refreshToken = "r",
            apiKey = null,
            nowIso = "2026-09-01T00:00:00Z",
        ).toString()
        val (ok, printed) = persist(authPath, json)
        assertTrue(ok, "the real codex builder's output must count as signed in: $printed")
        assertTrue(Files.readString(authPath).contains("tok_codex"))
    }

    @Test
    fun `the grok on-disk shape, token under tokens, is a sign-in - DR-172`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val json = GrokOAuth().grokAuthJsonFromTokenResponse(
            """{"access_token":"tok_grok","refresh_token":"r","expires_in":3600}""",
            fallbackRefresh = null,
            nowMs = 1_000L,
            nowIso = "2026-09-01T00:00:00Z",
        ).toString()
        val (ok, printed) = persist(authPath, json)
        assertTrue(ok, "the real grok builder's output must count as signed in: $printed")
        assertTrue(Files.readString(authPath).contains("tok_grok"))
    }

    @Test
    fun `the kimi shape is a sign-in - DR-172 control`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val json = KimiOAuth().kimiAuthJsonFromTokenResponse(
            """{"access_token":"tok_kimi","refresh_token":"r","expires_in":3600}""",
            1_000L,
        ).toString()
        val (ok, printed) = persist(authPath, json)
        assertTrue(ok, printed)
        assertTrue(Files.readString(authPath).contains("tok_kimi"))
    }

    // JsonNull is a JsonPrimitive whose content is the string "null": a null token must not read as one.
    @Test
    fun `a JSON null token under tokens is not a sign-in - DR-172`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val (ok, printed) = persist(authPath, """{"tokens":{"access_token":null,"refresh_token":"r"}}""")
        assertFalse(ok, printed)
        assertFalse(Files.exists(authPath), "nothing may be written for a null token: $printed")
    }
}

class LoginCollisionTest {
    @Test
    fun `a successful codex exchange survives retained quota and occupied suffixes`(@TempDir tmp: Path) {
        for (danglingQuota in listOf(false, true)) {
            val home = Files.createDirectories(tmp.resolve(danglingQuota.toString()))
            val primary = home.resolve("codex.json")
            Files.writeString(primary, "{}")
            val spec = LoginCodex().spec("codex", primary, "auto")
            val label = OAuthAccountLabels.chatGpt("plus", "private-account-id")
            val pool = Files.createDirectories(home.resolve("chatgpt-oauth/codex.json"))
            val quota = pool.resolve("$label-quota.json")
            if (danglingQuota) {
                Files.createSymbolicLink(quota, home.resolve("gone"))
            } else {
                Files.writeString(quota, "retained quota")
            }
            val occupied = pool.resolve("$label-2.json")
            Files.writeString(occupied, "existing credential")
            Files.createSymbolicLink(pool.resolve("$label-3-quota.json"), home.resolve("gone-quota"))
            Files.createSymbolicLink(pool.resolve("$label-4.json"), home.resolve("gone-credential"))

            val (ok, printed) = exchange(spec, tokenResponse())

            assertTrue(ok, printed)
            val destination = pool.resolve("$label-5.json")
            val saved = Json.parseToJsonElement(Files.readString(destination)).jsonObject
            assertEquals("$label-5", saved["splice_account_label"]?.jsonPrimitive?.content)
            assertEquals("refresh-secret", saved["tokens"]?.jsonObject?.get("refresh_token")?.jsonPrimitive?.content)
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(destination))
            assertEquals("{}", Files.readString(primary))
            assertEquals("existing credential", Files.readString(occupied))
            assertTrue(Files.exists(quota, LinkOption.NOFOLLOW_LINKS))
            if (danglingQuota) {
                assertTrue(Files.isSymbolicLink(quota))
            } else {
                assertEquals("retained quota", Files.readString(quota))
            }
            assertFalse(Files.exists(pool.resolve("$label-5-quota.json"), LinkOption.NOFOLLOW_LINKS))
            assertTrue(printed.contains("retained quota in $label-quota.json — saved credentials as $label-5.json"))
            assertTrue(printed.contains("signed in — credentials written to $destination"))
            assertFalse(printed.contains("token exchange error"), printed)
            assertFalse(printed.contains("private-account-id"), printed)
            assertFalse(printed.contains("refresh-secret"), printed)
        }
    }

    @Test
    fun `repeated codex repair reuses the same identity rather than growing the pool`(@TempDir tmp: Path) {
        val primary = tmp.resolve("codex.json")
        Files.writeString(primary, "{}")
        val store = OAuthAccountFiles()
        val label = OAuthAccountLabels.chatGpt("plus", "private-account-id")
        val pool = Files.createDirectories(store.poolDir(AuthKind.ChatgptOAuth, primary))
        Files.writeString(pool.resolve("$label-quota.json"), "orphaned base quota")
        val other = Json.parseToJsonElement(
            """{"tokens":{"access_token":"other-token","account_id":"other-private-account"}}""",
        ).jsonObject
        store.writeLabeled(AuthKind.ChatgptOAuth, primary, "$label-2", other)
        val spec = LoginCodex().spec("codex", primary, "auto")

        val first = exchange(spec, tokenResponse())
        assertTrue(first.first, first.second)
        Files.writeString(pool.resolve("$label-3-quota.json"), "same identity quota")
        val second = exchange(LoginCodex().spec("codex", primary, "auto"), tokenResponse("renewed-refresh"))

        assertTrue(second.first, second.second)
        assertEquals(
            listOf("primary", "$label-2", "$label-3"),
            store.discover(AuthKind.ChatgptOAuth, primary).map { it.label },
        )
        assertTrue(Files.readString(pool.resolve("$label-3.json")).contains("renewed-refresh"))
        assertTrue(Files.readString(pool.resolve("$label-2.json")).contains("other-token"))
        assertFalse(Files.exists(pool.resolve("$label-4.json")))
        assertEquals("same identity quota", Files.readString(pool.resolve("$label-3-quota.json")))
        assertEquals("orphaned base quota", Files.readString(pool.resolve("$label-quota.json")))
        assertTrue(second.second.contains("saved credentials as $label-3.json"))
        assertFalse(second.second.contains("private-account"))

        Files.delete(pool.resolve("$label-quota.json"))
        val third = exchange(LoginCodex().spec("codex", primary, "auto"), tokenResponse("third-refresh"))
        assertTrue(third.first, third.second)
        assertEquals(
            listOf("primary", "$label-2", "$label-3"),
            store.discover(AuthKind.ChatgptOAuth, primary).map { it.label },
        )
        assertFalse(Files.exists(pool.resolve("$label.json")))
        assertTrue(Files.readString(pool.resolve("$label-3.json")).contains("third-refresh"))
        assertEquals("same identity quota", Files.readString(pool.resolve("$label-3-quota.json")))
    }

    @Test
    fun `linked base credential cannot inherit retained quota on repair`(@TempDir tmp: Path) {
        val primary = tmp.resolve("codex.json")
        Files.writeString(primary, "{}")
        val store = OAuthAccountFiles()
        val label = OAuthAccountLabels.chatGpt("plus", "private-account-id")
        val pool = Files.createDirectories(store.poolDir(AuthKind.ChatgptOAuth, primary))
        val linked = tmp.resolve("different-credential.json")
        val old = store.decorated(
            AuthKind.ChatgptOAuth,
            label,
            Json.parseToJsonElement(
                """{"tokens":{"access_token":"old-token","account_id":"private-account-id"}}""",
            ).jsonObject,
        ).toString()
        Files.writeString(linked, old)
        Files.createSymbolicLink(pool.resolve("$label.json"), linked)
        Files.writeString(pool.resolve("$label-quota.json"), "orphaned quota")

        val (ok, printed) = exchange(LoginCodex().spec("codex", primary, "auto"), tokenResponse())

        assertTrue(ok, printed)
        assertTrue(Files.isSymbolicLink(pool.resolve("$label.json")))
        assertEquals(old, Files.readString(linked))
        assertEquals("orphaned quota", Files.readString(pool.resolve("$label-quota.json")))
        assertTrue(Files.exists(pool.resolve("$label-2.json")))
        assertFalse(Files.exists(pool.resolve("$label-2-quota.json")))
        assertEquals(
            listOf("primary", "$label-2"),
            store.discover(AuthKind.ChatgptOAuth, primary).map { it.label },
        )
    }

    @Test
    fun `a persistence failure after successful exchange is not a token exchange error`(@TempDir tmp: Path) {
        val primary = Files.createDirectory(tmp.resolve("credential-directory"))
        val spec = LoginCodex().spec("codex", primary)

        val (ok, printed) = exchange(spec, tokenResponse())

        assertFalse(ok)
        assertTrue(printed.contains("credential persistence error:"), printed)
        assertFalse(printed.contains("token exchange error"), printed)
        assertFalse(printed.contains("refresh-secret"), printed)
    }

    @Test
    fun `existing credential and quota keep their label on token-derived relogin`(@TempDir tmp: Path) {
        val primary = tmp.resolve("codex.json")
        val store = OAuthAccountFiles()
        val old = Json.parseToJsonElement("""{"access_token":"old-token"}""").jsonObject
        val target = store.writeLabeled(AuthKind.ChatgptOAuth, primary, "plus-safe", old)
        val quota = target.resolveSibling("plus-safe-quota.json")
        Files.writeString(quota, "retained quota")
        val fresh = Json.parseToJsonElement("""{"access_token":"new-token"}""").jsonObject

        val written = store.writeTokenDerived(AuthKind.ChatgptOAuth, primary, "plus-safe", fresh)

        assertEquals(target, written.file)
        assertEquals(null, written.retainedQuota)
        assertEquals("retained quota", Files.readString(quota))
        assertTrue(Files.readString(target).contains("new-token"))
        assertFalse(Files.exists(target.resolveSibling("plus-safe-2.json")))
    }

    @Test
    fun `collision suffix stays within the shared label limit`(@TempDir tmp: Path) {
        val primary = tmp.resolve("codex.json")
        val store = OAuthAccountFiles()
        val pool = Files.createDirectories(store.poolDir(AuthKind.ChatgptOAuth, primary))
        val label = "a".repeat(48)
        Files.writeString(pool.resolve("$label-quota.json"), "{}")
        val providerJson = Json.parseToJsonElement("""{"access_token":"test-token"}""").jsonObject

        val written = store.writeTokenDerived(AuthKind.ChatgptOAuth, primary, label, providerJson)

        val actual = written.file.fileName.toString().removeSuffix(".json")
        assertEquals("a".repeat(46) + "-2", actual)
        assertTrue(AccountLabelPolicy.isSafe(actual))
    }

    private fun tokenResponse(refreshToken: String = "refresh-secret"): String {
        val claims = """{"https://api.openai.com/auth":{
            "chatgpt_account_id":"private-account-id","chatgpt_plan_type":"plus"}}"""
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
        val token = "header.$payload.signature"
        return """{"access_token":"$token","id_token":"$token","refresh_token":"$refreshToken"}"""
    }

    private fun exchange(spec: LoginSpec, body: String): Pair<Boolean, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { response ->
            val bytes = body.toByteArray()
            response.sendResponseHeaders(200, bytes.size.toLong())
            response.responseBody.use { it.write(bytes) }
        }
        server.start()
        val out = StringBuilder()
        val flow = OAuthLoginFlow(TerminalOutput { out.appendLine(it) })
        return try {
            val local = spec.copy(tokenUrl = "http://127.0.0.1:${server.address.port}/token")
            runBlocking { flow.exchangeAndPersist(local, "test-code") } to out.toString()
        } finally {
            server.stop(0)
        }
    }
}
