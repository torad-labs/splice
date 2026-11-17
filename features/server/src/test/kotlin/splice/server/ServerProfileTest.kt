// The profile a laptop builds its commands from (docs/specs/server-mode.md, F7), asked over the listener as a key
// holder asks it: each command `[server] commands` names with every model's window, whose key asked, the sign-in
// without its client secret, and no telemetry relay or client minimum on a server that has neither.
package splice.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.core.topology.ServerSignin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

internal class ServerProfileTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val rig = ServerRig()
    private val issuer = StandInIssuer()

    /** This machine's two heads: `claude-team` on two models and `claude-own` on one. */
    private val heads = listOf(
        StandInHead(TestPorts.reserve(), windows = mapOf("big" to 200_000L, "small" to 64_000L)),
        StandInHead(TestPorts.reserve(), key = "own", command = "claude-own", windows = mapOf("own" to 32_000L)),
    )

    @AfterEach
    fun stop() {
        rig.close()
        issuer.close()
    }

    private fun profile(key: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/server/profile"))
        key?.let { request.header("authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun read(key: String): JsonObject {
        val answer = profile(key)
        assertEquals(200, answer.statusCode(), answer.body())
        return Json.parseToJsonElement(answer.body()).jsonObject
    }

    /** Each command the profile lists, with its models' windows in the order its launch offers them. */
    private fun commands(profile: JsonObject): Map<String, List<Pair<String, Long>>> =
        profile.getValue("commands").jsonArray.associate { entry ->
            val command = entry.jsonObject
            command.getValue("command").jsonPrimitive.content to command.getValue("models").jsonArray.map { model ->
                val row = model.jsonObject
                row.getValue("id").jsonPrimitive.content to row.getValue("context_window").jsonPrimitive.long
            }
        }

    @Test
    fun `a key holder reads each served command with its models' windows, whose key it is, and the sign-in`() {
        val signin = ServerSignin(issuer.url, issuer.clientId, "SIGNIN_SECRET", listOf("team.example"))
        val server = rig.config.copy(commands = listOf("claude-team", "claude-own"), signin = signin)
        rig.open(state, heads, server, signinSecret = issuer.clientSecret)
        val key = rig.keyFor("ann@team.example", expires = "30d")

        val answer = profile(key)
        assertEquals("application/json", answer.headers().firstValue("content-type").orElse("").substringBefore(';'))
        val read = read(key)
        val served = mapOf(
            "claude-team" to listOf("big" to 200_000L, "small" to 64_000L),
            "claude-own" to listOf("own" to 32_000L),
        )
        assertEquals(served, commands(read))
        val holder = read.getValue("key").jsonObject
        assertEquals("ann@team.example", holder.getValue("person").jsonPrimitive.content)
        assertEquals(key.split('_').take(2).joinToString("_"), holder.getValue("prefix").jsonPrimitive.content)
        assertFalse(holder.getValue("expires_at") is JsonNull, "a key with an expiry is told none")
        val told = read.getValue("signin").jsonObject
        assertEquals(issuer.url, told.getValue("issuer").jsonPrimitive.content)
        assertEquals(issuer.clientId, told.getValue("client_id").jsonPrimitive.content)
        assertEquals(listOf("team.example"), told.getValue("domains").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(answer.body().contains(issuer.clientSecret), "the profile holds the client secret")
        assertFalse(answer.body().contains(key), "the profile repeats the key")
        assertEquals(JsonNull, read.getValue("telemetry"))
        assertEquals(JsonNull, read.getValue("client").jsonObject.getValue("minimum"))
    }

    @Test
    fun `a head the server does not serve is not in it, and a server nobody signs in to says null`() {
        rig.open(state, heads)
        val read = read(rig.keyFor("bo@team.example"))
        assertEquals(listOf("claude-team"), commands(read).keys.toList())
        assertEquals(JsonNull, read.getValue("signin"))
    }

    @Test
    fun `no key, a key the server never issued and a revoked key are each refused 401`() {
        val mode = rig.open(state, heads)
        val key = rig.keyFor("cy@team.example")
        assertEquals(401, profile(null).statusCode())
        assertEquals(401, profile("spk_00000000_notakeynotakeynotakeynotakeynotakey").statusCode())
        assertEquals(200, profile(key).statusCode())
        runBlocking { mode.keyApi.revoke(key.split('_').take(2).joinToString("_")) }
        assertEquals(401, profile(key).statusCode())
    }
}
