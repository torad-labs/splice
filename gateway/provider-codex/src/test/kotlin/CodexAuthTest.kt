// PORT-OF: server/test/codex-login.test.mjs + codex-oauth pins @ 4ca99f7 — PKCE shape,
// authorize-URL param order + %20 (never +) encoding, JWT claim extraction, auth.json shape,
// cached read (mtime+TTL), single-flight refresh preserving other fields + 0600, masked
// introspection.
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import splice.provider.codex.CodexAuthProvider
import splice.provider.codex.RefreshedTokens
import splice.provider.codex.accountIdFromIdToken
import splice.provider.codex.authJsonFromTokens
import splice.provider.codex.buildAuthorizeUrl
import splice.provider.codex.makePkce
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun jwt(payloadJson: String): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
    val payload = enc.encodeToString(payloadJson.toByteArray())
    return "$header.$payload.sig"
}

class CodexAuthTest {

    private val noEnv: (String) -> String? = { null }

    @Test
    fun `pkce - verifier and challenge are url-safe base64 without padding`() {
        val p = makePkce()
        assertTrue(p.verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(p.challenge.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(p.verifier.contains("=") || p.challenge.contains("="))
    }

    @Test
    fun `authorize url - exact param order and percent-not-plus encoding`() {
        val url = buildAuthorizeUrl(challenge = "CH", state = "ST", clientId = "cid", env = noEnv)
        val query = url.substringAfter("?")
        val keys = query.split("&").map { it.substringBefore("=") }
        assertEquals(
            listOf(
                "response_type", "client_id", "redirect_uri", "scope", "code_challenge",
                "code_challenge_method", "id_token_add_organizations", "codex_cli_simplified_flow",
                "state", "originator",
            ),
            keys,
        )
        // the CLI-parity gotcha: scope spaces are %20, never +
        assertTrue(query.contains("scope=openid%20profile%20email%20offline_access"))
        assertFalse(query.contains("+"))
        assertTrue(url.startsWith("https://auth.openai.com/oauth/authorize?"))
    }

    @Test
    fun `jwt claim extraction pulls the chatgpt account id`() {
        val token = jwt("""{"email":"x@y.z","https://api.openai.com/auth":{"chatgpt_account_id":"acct-1234-5678"}}""")
        assertEquals("acct-1234-5678", accountIdFromIdToken(token))
        assertNull(accountIdFromIdToken("garbage"))
    }

    @Test
    fun `auth json shape is codex-cli-compatible`() {
        val idToken = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-9"}}""")
        val obj = authJsonFromTokens(idToken, "access-1", "refresh-1", apiKey = "sk-x", nowIso = "2026-07-16T00:00:00Z")
        assertEquals("sk-x", obj["OPENAI_API_KEY"]?.jsonPrimitive?.content)
        val tokens = obj["tokens"]!!.jsonObject
        assertEquals("access-1", tokens["access_token"]?.jsonPrimitive?.content)
        assertEquals("refresh-1", tokens["refresh_token"]?.jsonPrimitive?.content)
        assertEquals("acct-9", tokens["account_id"]?.jsonPrimitive?.content)
        assertEquals("2026-07-16T00:00:00Z", obj["last_refresh"]?.jsonPrimitive?.content)
    }

    private fun provider(
        tmp: Path,
        clock: () -> Long,
        refresh: suspend (String) -> RefreshedTokens?,
    ): Pair<CodexAuthProvider, Path> {
        val authPath = tmp.resolve(".codex/auth.json")
        return CodexAuthProvider(
            authPath = authPath,
            authCacheMs = 60_000,
            clock = clock,
            nowIso = { "2026-07-16T00:00:00Z" },
            refreshCall = refresh,
        ) to authPath
    }

    @Test
    fun `cached read keys on mtime and ttl`(@TempDir tmp: Path) = runTest {
        var now = 1_000L
        val (auth, path) = provider(tmp, { now }) { null }
        Files.createDirectories(path.parent)
        path.writeText("""{"tokens":{"access_token":"tok-1","account_id":"acct-1"}}""")
        val first = auth.credentials() as Credentials.Bearer
        assertEquals("tok-1", first.token)
        assertEquals("acct-1", first.accountId)
        // external rewrite with a new mtime -> re-read even within TTL
        Thread.sleep(5)
        path.writeText("""{"tokens":{"access_token":"tok-2"}}""")
        assertEquals("tok-2", (auth.credentials() as Credentials.Bearer).token)
    }

    @Test
    fun `single-flight refresh preserves other fields, writes 0600, one refresh call`(@TempDir tmp: Path) = runTest {
        val calls = AtomicInteger(0)
        // gate: the leader's refresh blocks until every caller has registered, so the
        // single-flight dedup is exercised deterministically (not scheduler-dependent).
        val gate = CompletableDeferred<Unit>()
        val (auth, path) = provider(tmp, { 1_000L }) {
            calls.incrementAndGet()
            gate.await()
            RefreshedTokens(accessToken = "tok-new", refreshToken = "refresh-2", idToken = "id-2")
        }
        Files.createDirectories(path.parent)
        path.writeText(
            """{"OPENAI_API_KEY":"sk-keep","tokens":{"access_token":"tok-old",
                "refresh_token":"refresh-1","account_id":"acct-keep"},"last_refresh":"old"}""",
        )
        // N concurrent refreshes -> ONE refresh call
        val pending = (1..5).map { async { auth.refresh() } }
        repeat(10) { yield() } // let all five register as leader/followers
        gate.complete(Unit)
        val results = pending.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(results.all { (it as Credentials.Bearer).token == "tok-new" })
        val onDisk = kotlinx.serialization.json.Json.parseToJsonElement(path.readText()).jsonObject
        assertEquals("sk-keep", onDisk["OPENAI_API_KEY"]?.jsonPrimitive?.content)
        val tokens = onDisk["tokens"]!!.jsonObject
        assertEquals("tok-new", tokens["access_token"]?.jsonPrimitive?.content)
        assertEquals("refresh-2", tokens["refresh_token"]?.jsonPrimitive?.content)
        assertEquals("acct-keep", tokens["account_id"]?.jsonPrimitive?.content) // preserved
        assertEquals("2026-07-16T00:00:00Z", onDisk["last_refresh"]?.jsonPrimitive?.content)
        val perms = Files.getPosixFilePermissions(path).toString()
        assertEquals("[OWNER_READ, OWNER_WRITE]", perms)
    }

    @Test
    fun `describe masks the account id and never exposes tokens`(@TempDir tmp: Path) = runTest {
        val (auth, path) = provider(tmp, { 1L }) { null }
        assertFalse(auth.describe().present)
        Files.createDirectories(path.parent)
        path.writeText("""{"tokens":{"access_token":"secret","account_id":"acct12345678"},"last_refresh":"then"}""")
        val d = auth.describe()
        assertTrue(d.present)
        assertEquals("chatgpt-oauth", d.kind)
        assertEquals("acct…5678", d.fields["account_id_masked"])
        assertEquals("then", d.fields["last_refresh"])
        assertFalse(d.fields.values.any { it.contains("secret") })
    }
}
