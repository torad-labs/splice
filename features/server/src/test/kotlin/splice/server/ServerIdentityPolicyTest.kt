package splice.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerForwardAuth
import splice.core.topology.ServerSignin
import splice.core.util.EnvReader
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

internal class ServerIdentityPolicyTest {
    @TempDir lateinit var state: Path

    private val clock = SetClock()
    private val rig = ServerRig(clock = clock)
    private val issuer = StandInIssuer()
    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun stop() {
        rig.close()
        issuer.close()
    }

    @Test
    fun `admin access requires a client in the allowlist and trusts only configured proxy hops`() {
        rig.open(
            state,
            emptyList(),
            rig.config.copy(admins = listOf(ADMIN), adminCidrs = listOf("203.0.113.0/24")),
        )
        val key = rig.keyFor(ADMIN)
        fun adminFrom(chain: String): Int =
            send("/server/api/keys", key, header = "X-Forwarded-For" to chain).statusCode()

        assertEquals(403, adminFrom("203.0.113.8"))
        rig.close()
        rig.open(
            state,
            emptyList(),
            rig.config.copy(
                admins = listOf(ADMIN),
                adminCidrs = listOf("203.0.113.0/24", "127.0.0.1"),
                trustedProxies = listOf("127.0.0.1", "192.0.2.0/24"),
            ),
        )
        assertEquals(200, adminFrom("203.0.113.8, 192.0.2.4"))
        assertEquals(403, adminFrom("203.0.113.8, 198.51.100.2"))
        assertEquals(403, adminFrom("203.0.113.8, invalid"))
        assertEquals(403, send("/server/metrics", key).statusCode())
        assertEquals(200, send("/", key).statusCode())
    }

    @Test
    fun `imported hashes keep their original keys and cannot restore revoked ones`() {
        val config = rig.config.copy(admins = listOf(ADMIN), keyPrefix = "team_")
        rig.open(state, emptyList(), config)
        val admin = rig.keyFor(ADMIN)
        assertTrue(admin.startsWith("team_"))
        val prefix = "spk_fixture0"
        val key = prefix + "_" + "a".repeat(40)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
        val row = """{"person":"ada@team.example","prefix":"$prefix","hash":"$digest"}"""
        val importPath = "/server/api/keys/import"
        assertEquals(401, send(importPath, method = "POST", body = row).statusCode())
        val invalid = send(importPath, admin, method = "POST", body = row.replace(digest, "not-a-hash"))
        assertEquals(400, invalid.statusCode())
        val accepted = send(importPath, admin, method = "POST", body = row)
        assertEquals(201, accepted.statusCode())
        assertFalse(digest in accepted.body())
        assertEquals(200, send("/", key).statusCode())
        rig.close()
        rig.open(state, emptyList(), config.copy(keyPrefix = "next_"))
        assertEquals(200, send("/", key).statusCode())
        assertEquals(200, send("/server/api/keys/$prefix", admin, method = "DELETE").statusCode())
        assertEquals(409, send(importPath, admin, method = "POST", body = row).statusCode())
        assertEquals(401, send("/", key).statusCode())
    }

    @Test
    fun `an imported key with a secret of 32 to 128 characters opens the server, and outside that range is no key`() {
        val config = rig.config.copy(admins = listOf(ADMIN))
        rig.open(state, emptyList(), config)
        val admin = rig.keyFor(ADMIN)
        for ((prefix, length) in listOf("spk_short032" to 32, "spk_longer64" to 64, "spk_max00128" to 128)) {
            val key = prefix + "_" + "b".repeat(length)
            val row = """{"person":"ada@team.example","prefix":"$prefix","hash":"${sha256(key)}"}"""
            assertEquals(201, send("/server/api/keys/import", admin, method = "POST", body = row).statusCode())
            assertEquals(200, send("/", key).statusCode(), "an imported $length-character secret did not verify")
        }
        val over = "spk_over0129_" + "b".repeat(129)
        val overRow = """{"person":"ada@team.example","prefix":"spk_over0129","hash":"${sha256(over)}"}"""
        assertEquals(201, send("/server/api/keys/import", admin, method = "POST", body = overRow).statusCode())
        assertEquals(401, send("/", "spk_short032_" + "b".repeat(31)).statusCode())
        assertEquals(401, send("/", over).statusCode())
    }

    @Test
    fun `an expired browser session leaves the key list at the next sign-in, and a revoked one stays`() {
        openBrowser()
        val admin = rig.keyFor(ADMIN)
        fun browserRows(): List<Pair<String, Boolean>> =
            Json.parseToJsonElement(send("/server/api/keys?person=ada@team.example", admin).body()).jsonObject
                .getValue("keys").jsonArray.map { it.jsonObject }
                .filter { it.getValue("source").jsonPrimitive.content == "browser" }
                .map { it.getValue("prefix").jsonPrimitive.content to (it.getValue("revoked_at") !is JsonNull) }
        session()
        val revoked = browserRows().single().first
        assertEquals(200, send("/server/api/keys/$revoked", admin, method = "DELETE").statusCode())
        session()
        val lapsed = browserRows().single { !it.second }.first
        clock.pass(61)
        session()
        val rows = browserRows()
        assertEquals(2, rows.size, "expired sessions pile up in the key list: $rows")
        assertTrue(rows.none { it.first == lapsed }, "the expired session is still listed")
        assertTrue(rows.contains(revoked to true), "the revoked session was forgotten")
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    @Test
    fun `browser sign-in checks hosted domain and verified email before issuing a revocable dashboard cookie`() {
        openBrowser()
        assertEquals(400, send("$FORWARD/login?return_to=%2F%2Felsewhere.example").statusCode())
        assertEquals(400, send("$FORWARD/login?return_to=%2F%255Celsewhere.example").statusCode())
        assertEquals(401, send(FORWARD).statusCode())
        val flow = begin()
        val mismatched = callback(flow, Identity("ada@team.example", hostedDomain = DOMAIN), state = "wrong")
        assertEquals(401, mismatched.statusCode())
        assertEquals(401, callback(begin(), Identity("ada@team.example")).statusCode())
        assertEquals(401, callback(begin(), Identity("ada@team.example", hostedDomain = "other.example")).statusCode())
        assertEquals(401, callback(begin(), Identity("ada@other.example", hostedDomain = DOMAIN)).statusCode())
        val unverified = Identity("ada@team.example", verified = false, hostedDomain = DOMAIN)
        assertEquals(401, callback(begin(), unverified).statusCode())
        assertEquals(403, callback(begin(), Identity("denied@team.example", hostedDomain = DOMAIN)).statusCode())

        val cookie = session()
        val check = send(FORWARD, header = "Cookie" to cookie)
        assertEquals(200, check.statusCode())
        assertEquals("ada@team.example", header(check, "Remote-User"))
        assertEquals("user", header(check, "X-Dashboard-Role"))
        assertEquals(401, send(FORWARD, header = "Cookie" to (cookie + "x")).statusCode())
        assertEquals(401, send("/", header = "Cookie" to cookie).statusCode())
        val admin = rig.keyFor(ADMIN)
        val listed = Json.parseToJsonElement(send("/server/api/keys?person=ada@team.example", admin).body()).jsonObject
        val prefix = listed.getValue("keys").jsonArray.single().jsonObject.getValue("prefix").jsonPrimitive.content
        assertEquals(200, send("/server/api/keys/$prefix", admin, method = "DELETE").statusCode())
        assertEquals(401, send(FORWARD, header = "Cookie" to cookie).statusCode())
    }

    @Test
    fun `the laptop token route refuses non-loopback redirects`() {
        openBrowser()
        val body = """
            {"code":"fixture","code_verifier":"verifier","redirect_uri":"https://dash.example/server/forward-auth/callback",
             "nonce":"fixture-nonce","device":"laptop"}
        """.trimIndent()
        val response = send("/server/signin/token", method = "POST", body = body)
        assertEquals(401, response.statusCode())
        assertTrue(response.body().contains("loopback callback"))
    }

    @Test
    fun `browser session expiry is enforced by the auth endpoint`() {
        openBrowser()
        val cookie = session()
        clock.pass(60)
        assertEquals(401, send(FORWARD, header = "Cookie" to cookie).statusCode())
    }

    @Test
    fun `browser sign-in can use a web registration distinct from the laptop client`() {
        openBrowser(webClient = issuer.clientId)
        assertEquals(200, send(FORWARD, header = "Cookie" to session()).statusCode())
    }

    private fun openBrowser(webClient: String? = null) {
        val separate = webClient != null
        rig.open(
            state,
            emptyList(),
            rig.config.copy(
                admins = listOf(ADMIN),
                signin = ServerSignin(
                    issuer = issuer.url,
                    clientId = if (separate) "laptop-client" else issuer.clientId,
                    domains = listOf(DOMAIN),
                    hostedDomain = DOMAIN,
                ),
                forwardAuth = ServerForwardAuth(
                    allow = listOf("ada@team.example"),
                    browserOrigin = "https://dash.example",
                    sessionSeconds = 60,
                    emailHeader = "Remote-User",
                    roleHeader = "X-Dashboard-Role",
                    clientId = webClient,
                    clientSecretEnv = BROWSER_SECRET_ENV.takeIf { separate },
                ),
            ),
            signinSecret = if (separate) null else issuer.clientSecret,
            env = EnvReader { name -> issuer.clientSecret.takeIf { name == BROWSER_SECRET_ENV } },
        )
    }

    private fun session(): String {
        val answer = callback(begin(), Identity("ada@team.example", hostedDomain = DOMAIN))
        assertEquals(303, answer.statusCode(), answer.body())
        assertEquals("https://dash.example/reports", header(answer, "Location"))
        val cookie = answer.headers().allValues("Set-Cookie").single { it.startsWith("__Host-splice-forward=") }
        for (flag in listOf("Secure", "HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=60")) {
            assertTrue(cookie.split("; ").contains(flag))
        }
        return cookie.substringBefore(';')
    }

    private fun begin(): BrowserFlow {
        val answer = send("$FORWARD/login?return_to=%2Freports")
        assertEquals(303, answer.statusCode(), answer.body())
        val params = URI.create(header(answer, "Location")).rawQuery.split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
        return BrowserFlow(params, header(answer, "Set-Cookie").substringBefore(';'))
    }

    private fun callback(
        flow: BrowserFlow,
        identity: Identity,
        state: String = flow.params.getValue("state"),
    ): HttpResponse<String> {
        val code = issuer.code(
            identity,
            flow.params.getValue("code_challenge"),
            flow.params.getValue("nonce"),
            flow.params.getValue("redirect_uri"),
        )
        return send("$FORWARD/callback?state=$state&code=$code", header = "Cookie" to flow.cookie)
    }

    private fun send(
        path: String,
        key: String? = null,
        header: Pair<String, String>? = null,
        method: String = "GET",
        body: String? = null,
    ): HttpResponse<String> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}$path"))
            .timeout(Duration.ofSeconds(10)).method(method, publisher)
        key?.let { request.header("x-api-key", it) }
        header?.let { request.header(it.first, it.second) }
        body?.let { request.header("Content-Type", "application/json") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun header(response: HttpResponse<String>, name: String): String =
        response.headers().firstValue(name).orElse("")

    private data class BrowserFlow(val params: Map<String, String>, val cookie: String)
}

private const val BROWSER_SECRET_ENV = "DASHBOARD_TEST_SECRET"
private const val DOMAIN = "team.example"
private const val ADMIN = "admin@team.example"
private const val FORWARD = "/server/forward-auth"
