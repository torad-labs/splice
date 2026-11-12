// NEW: the model /api/accounts names as a command's own (`usual_model`), which Accounts and `splice status` name the
// command's next login for. Read from the reply the daemon serves, after real turns through the head.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.client.AGENT_HEADER
import splice.core.config.LAUNCH_PROOF_HEADER
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class ClaudeUsualModelTest {
    @TempDir
    lateinit var home: Path

    /** One turn of [model]; [agent] is the subagent Claude Code says it is sending for, null for a session's own. */
    private suspend fun turn(client: HttpClient, rig: ClaudeNativePoolFixture.Rig, model: String, agent: String?) {
        val response = client.post("http://127.0.0.1:${rig.head.head.port}/v1/messages") {
            bearerAuth("synthetic-caller")
            header(LAUNCH_PROOF_HEADER, rig.proof)
            header("x-claude-code-session-id", "working")
            agent?.let { header(AGENT_HEADER, it) }
            contentType(ContentType.Application.Json)
            setBody(
                """{"model":"$model","stream":false,"max_tokens":32,""" +
                    """"messages":[{"role":"user","content":"synthetic turn"}]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun `a session's helpers do not change the model the accounts reply names as the command's`() = runBlocking {
        val fixture = ClaudeNativePoolFixture(home).also {
            it.seed("native")
            it.addLogin()
        }
        val upstream = fixture.nativeUpstream(CopyOnWriteArrayList(), CopyOnWriteArrayList()).start()
        val port = upstream.engine.resolvedConnectors().single().port
        val rig = fixture.rig(upstreamUrl = "http://127.0.0.1:$port")
        try {
            // A session's own turn, then three from the helper it handed its reading to, on a smaller model.
            HttpClient(Java).use { client ->
                turn(client, rig, "synthetic-model", agent = null)
                repeat(3) { turn(client, rig, "small-helper", agent = "helper-1") }
            }
            val pool = Json.parseToJsonElement(rig.server.accountRoster().json()).jsonObject
                .getValue("head_pools").jsonObject.getValue(NATIVE_HEAD).jsonObject.getValue("account_pool").jsonObject
            assertEquals("synthetic-model", pool.getValue("usual_model").jsonPrimitive.content)
            assertEquals(
                listOf("small-helper", "synthetic-model"),
                pool.getValue("asked_models").jsonArray.map { it.jsonPrimitive.content },
                "every model asked for in the last hour is named, the latest first",
            )
        } finally {
            rig.close()
            upstream.stop()
        }
    }
}
