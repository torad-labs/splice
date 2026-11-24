// The live side of UpgradeFrom064MultiHeadDaemonTest: a daemon on a 0.6.4 multi-head file over a fixture home, and
// the routes a person's commands and clients reach it by (a head's own port, the terminal's /door, Desktop's legacy
// /desktop, a command's launch, the console's knob view, and retiring a head), each read as what it answers.
package splice.app.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import splice.app.Daemon
import splice.app.desktop.setup.DesktopManagedPolicy
import splice.core.auth.RefreshAttempt
import splice.core.config.FileHeldKey
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** The head that shares its provider with the team's main head. */
internal const val SIBLING = "claude-realinfer-fast"

internal class UpgradeFrom064MultiHeadRig(private val tmp: Path) {
    val fixture = UpgradeFrom064MultiHeadFixture(tmp)
    val home: Path = Files.createDirectories(tmp.resolve("home"))
    private val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val logged = CopyOnWriteArrayList<String>()
    private val control get() = "http://127.0.0.1:${fixture.controlPort}"

    /** [checks] against a live daemon on [toml], over the fixture home and Desktop's managed policy under [tmp]. */
    fun <T> live(toml: String, checks: suspend () -> T): T = UserHome.within(home) {
        DesktopManagedPolicy.within(tmp.resolve("etc/managed-settings.json")) {
            runBlocking {
                val daemon = Daemon(
                    topology = TopologyLoader.parse(toml),
                    statePaths = statePaths,
                    log = { logged.add(it) },
                    refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
                )
                daemon.start()
                try {
                    runCatching { awaitListening(fixture.controlPort, *fixture.headPorts.toIntArray()) }.getOrElse {
                        throw AssertionError("${it.message}; daemon log:\n${logged.joinToString("")}", it)
                    }
                    checks()
                } finally {
                    daemon.stop()
                }
            }
        }
    }

    /** [head]'s [selector] (its pinned one unless named) sent to [at], a head's port or a door path on the control
     *  port, with [key] (the management key unless named) in the session of [head]'s provider; its text, once the turn
     *  reached [head]'s provider once as [upstream] with that provider's own key alone. */
    suspend fun turn(
        at: String,
        head: Head,
        key: String = mgmt(),
        selector: String = head.selector,
        upstream: String = head.upstream,
    ): String {
        val url = if (at.startsWith("/")) "$control$at/v1/messages" else "$at/v1/messages"
        val own = fixture.providerKeyOf(head.provider)
        val sent = head.provider.turns.size
        val response = client.post(url) {
            authorized(key)
            header("x-claude-code-session-id", "upgrade-from-064-$own")
            setBody(
                """{"model":"$selector","max_tokens":64,"stream":false,""" +
                    """"messages":[{"role":"user","content":"hi"}]}""",
            )
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, "$url $selector: $body")
        assertEquals(sent + 1, head.provider.turns.size, "$selector reached its own provider once")
        val seen = head.provider.turns.last()
        assertTrue("\"model\":\"$upstream\"" in seen.body.replace(" ", ""), "as $upstream: ${seen.body}")
        assertTrue(fixture.keys.getValue(own) in seen.everything, "$own is called with its own key")
        for ((name, other) in fixture.keys) if (name != own) assertFalse(other in seen.everything, "$name reached it")
        assertFalse(key in seen.everything, "the caller's key never reaches a provider")
        return Regex("(realinfer|codex|grok|kimi)-ok").find(body)?.value.orEmpty()
    }

    /** The model [command]'s launch starts on and the window its session holds. */
    suspend fun launch(command: String): Pair<String, String> {
        val response = client.post("$control/launch/$command") {
            authorized(mgmt())
            setBody("""{"args":[]}""")
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, "$command: $body")
        val env = json.parseToJsonElement(body).jsonObject.getValue("env").jsonObject
        return env.str("ANTHROPIC_MODEL") to env.str("CLAUDE_CODE_MAX_CONTEXT_TOKENS")
    }

    /** What the sibling head is served: its knob as the console reads it, and its command's model and window. */
    suspend fun sibling(): List<String> {
        val response = client.get("$control/api/config?head=$SIBLING") { authorized(mgmt()) }
        val config = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val knob = config.getValue("knobs").jsonObject.getValue("maxInflight").jsonObject.getValue("value")
        return listOf(knob.toString()) + launch(SIBLING).toList()
    }

    /** `splice claude <verb> claude-realinfer-fast`'s answer: retire or restore. */
    suspend fun act(verb: String): JsonObject {
        val response = client.post("$control/api/claude/$verb") {
            authorized(mgmt())
            setBody("""{"heads":["$SIBLING"]}""")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** The heads `splice claude check` says are retired. */
    suspend fun retired(): Set<String> {
        val response = client.get("$control/api/claude/heads") { authorized(mgmt()) }
        val heads = json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("heads").jsonArray
        return heads.map { it.jsonObject }.filter { it.getValue("retired").jsonPrimitive.boolean }
            .map { it.str("key") }.toSet()
    }

    /** `GET /health` on [port]. */
    suspend fun health(port: Int): Int = client.get("http://127.0.0.1:$port/health").status.value

    fun terminal(): String = FileHeldKey(statePaths.terminalKeyFile, "splice-terminal-", "claude", "", {}).get()

    private fun mgmt(): String = MgmtKey(statePaths).get()

    private fun HttpRequestBuilder.authorized(key: String) {
        header("Authorization", "Bearer $key")
        header("Content-Type", "application/json")
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    fun stop() {
        fixture.stop()
        client.close()
    }
}
