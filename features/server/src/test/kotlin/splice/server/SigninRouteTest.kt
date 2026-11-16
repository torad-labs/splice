// A team member's sign-in as the laptop meets it (docs/specs/server-mode.md, F2): over real HTTP to the listener's two
// open routes, against a stand-in identity provider on loopback. The config route gives the laptop everything and never
// the secret; the trade mints a per-device key living `key_days` only for a verified email in an allowed domain, and
// every wrong ID token is a 401 that mints nothing; a newer sign-in on a device replaces the older key at its first
// use; and one address past `rate_per_minute` is answered 429 with Retry-After.
package splice.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.core.topology.ServerSignin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal class SigninRouteTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val issuer = StandInIssuer()
    private val rig = ServerRig()
    private val base64 = Base64.getUrlEncoder().withoutPadding()
    private lateinit var admin: String

    private fun serve(keyDays: Int = KEY_DAYS, rate: Int = 0) {
        val signin = ServerSignin(issuer.url, issuer.clientId, "SIGNIN_SECRET", listOf("team.example"), keyDays, rate)
        val server = rig.config.copy(admins = listOf(ADMIN), signin = signin)
        rig.open(state, listOf(StandInHead(TestPorts.reserve())), server, signinSecret = issuer.clientSecret)
        admin = rig.keyFor(ADMIN)
    }

    @AfterEach
    fun stop() {
        rig.close()
        issuer.close()
    }

    private fun send(method: String, path: String, body: String? = null, key: String? = null): HttpResponse<String> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}$path"))
            .header("content-type", "application/json")
            .method(method, publisher)
            .also { builder -> key?.let { builder.header("x-api-key", it) } }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(response: HttpResponse<String>): JsonObject = Json.parseToJsonElement(response.body()).jsonObject

    private fun text(body: JsonObject, field: String): String = body.getValue(field).jsonPrimitive.content

    /** A laptop's sign-in as [who] on [device]: the config, the provider's code for the redirect, then the trade, with
     *  [verifier] or [nonce] in place of the ones the authorization was asked with when given. */
    private fun signIn(
        who: Identity,
        device: String,
        verifier: String? = null,
        nonce: String? = null,
    ): HttpResponse<String> {
        val config = json(send("GET", "/server/signin"))
        assertEquals(issuer.clientId, text(config, "client_id"))
        val ownVerifier = base64.encodeToString(UUID.randomUUID().toString().repeat(2).toByteArray())
        val challenge = base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(ownVerifier.toByteArray()))
        val ownNonce = UUID.randomUUID().toString()
        val code = issuer.code(who, challenge, ownNonce, REDIRECT)
        val trade = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier ?: ownVerifier)
            put("redirect_uri", REDIRECT)
            put("nonce", nonce ?: ownNonce)
            put("device", device)
        }
        return send("POST", "/server/signin/token", trade.toString())
    }

    /** [person]'s keys as an admin lists them over the listener. */
    private fun keysOf(person: String): List<JsonObject> =
        json(send("GET", "/server/api/keys?person=$person", key = admin))
            .getValue("keys").jsonArray.map { it.jsonObject }

    private fun opens(key: String): Int = send("GET", "/", key = key).statusCode()

    @Test
    fun `the sign-in config is open and gives a laptop everything it needs, and never the secret`() {
        serve()
        val answer = send("GET", "/server/signin")
        assertEquals(200, answer.statusCode())
        val config = json(answer)
        assertEquals(issuer.url, text(config, "issuer"))
        assertEquals("${issuer.url}/authorize", text(config, "authorization_endpoint"))
        val scopes = config.getValue("scopes").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(scopes.containsAll(listOf("openid", "email")), "$scopes")
        assertEquals(listOf("team.example"), config.getValue("domains").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(issuer.clientSecret in answer.body(), "the config carries the client secret")
    }

    @Test
    fun `a verified email in an allowed domain gets a key for its device that lives key_days and opens the server`() {
        serve()
        val answer = signIn(Identity("Dana@Team.Example"), "dana-laptop")
        assertEquals(200, answer.statusCode(), answer.body())
        val minted = json(answer)
        assertEquals("dana@team.example", text(minted, "person"))
        val lives = Duration.between(Instant.now(), Instant.parse(text(minted, "expires_at")))
        val days = (KEY_DAYS - 1) * HOURS..KEY_DAYS * HOURS
        assertTrue(lives.toHours() in days, "the key lives $lives, not $KEY_DAYS days")
        assertEquals(200, opens(text(minted, "key")))

        val row = keysOf("dana@team.example").single()
        assertEquals("signin", text(row, "source"))
        assertEquals("dana-laptop", text(row, "device"))
        val trade = issuer.tokenRequests.last()
        assertEquals(issuer.clientSecret, trade["client_secret"], "the server traded without its secret")
        assertTrue((trade["code_verifier"]?.length ?: 0) >= 43, "the server traded without the laptop's verifier")
    }

    @Test
    fun `every way the ID token or the trade is wrong is a 401 that mints nothing`() {
        serve()
        val wrong = listOf(
            Wrong("an email outside the allowed domains", Identity("out@other.example")),
            Wrong("an unverified email", Identity("unverified@team.example", verified = false)),
            Wrong("a key the provider does not publish", Identity("signature@team.example", foreignKey = true)),
            Wrong("an expired token", Identity("expired@team.example", expiresIn = -120)),
            Wrong("another audience", Identity("audience@team.example", aud = "someone-else")),
            Wrong("another issuer", Identity("issuer@team.example", iss = "https://issuer.invalid")),
            Wrong("another nonce", Identity("nonce@team.example"), nonce = "not-the-nonce"),
            Wrong("another verifier", Identity("pkce@team.example"), verifier = "x".repeat(43)),
        )
        for (each in wrong) {
            val (what, who) = each
            val refused = signIn(who, "laptop", each.verifier, each.nonce)
            assertEquals(401, refused.statusCode(), "$what: ${refused.body()}")
            val error = json(refused).getValue("error").jsonObject
            assertEquals("authentication_error", text(error, "type"), what)
            assertFalse("key" in json(refused), "$what answered a key")
            assertEquals(emptyList<JsonObject>(), keysOf(who.email.lowercase()), "$what minted a key")
        }
    }

    @Test
    fun `a newer sign-in keeps the older key on its device until the newer one is used, and leaves other devices be`() {
        serve()
        val first = text(json(signIn(Identity("dev@team.example"), "laptop")), "key")
        val desktop = text(json(signIn(Identity("dev@team.example"), "desktop")), "key")
        val second = text(json(signIn(Identity("dev@team.example"), "laptop")), "key")
        assertNotEquals(first, second)
        assertEquals(200, opens(first), "the older key stopped before the newer one was used")
        assertEquals(200, opens(second))
        assertEquals(401, opens(first), "the older key still works after the newer one was used")
        assertEquals(200, opens(desktop), "another device's key was revoked")
        assertEquals(200, opens(second))
    }

    @Test
    fun `sign-ins past rate_per_minute from one address are answered 429 with Retry-After`() {
        serve(rate = 3)
        val junk = """{"code":"junk","code_verifier":"junk","redirect_uri":"$REDIRECT","nonce":"n","device":"d"}"""
        val answers = List(4) { send("POST", "/server/signin/token", junk) }
        assertEquals(listOf(401, 401, 401, 429), answers.map { it.statusCode() })
        val retry = answers.last().headers().firstValue("retry-after").map(String::toLong).orElse(0)
        assertTrue(retry in 1..60, "Retry-After is $retry")
    }
}

/** One wrong sign-in: [what] is wrong, as [who] signs in, or with the trade's [verifier] or [nonce] replaced. */
private data class Wrong(val what: String, val who: Identity, val verifier: String? = null, val nonce: String? = null)

private const val ADMIN = "ops@team.example"
private const val REDIRECT = "http://127.0.0.1:1/callback"

// Not 30, so a server that ignored `key_days` for a month is caught.
private const val KEY_DAYS = 7
private const val HOURS = 24
