// real native-only wiring verifies the declared client head without a failover or credential mutation.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.ClientAuthProvider
import splice.core.auth.CredentialVerdict
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class ClaudeNativeHeadVerdictTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `a successful native-only request verifies the client head without failover or credential writes`() = runBlocking {
        val fixture = ClaudeNativePoolFixture(home)
        fixture.seed("splice")
        val file = home.resolve(".claude-splice/.credentials.json")
        val before = Files.readAllBytes(file)
        val sent = CopyOnWriteArrayList<String?>()
        val upstream = fixture.nativeUpstream(sent, CopyOnWriteArrayList()).start()
        val port = upstream.engine.resolvedConnectors().single().port
        val rig = fixture.rig(upstreamUrl = "http://127.0.0.1:$port")
        try {
            assertInstanceOf(ClientAuthProvider::class.java, rig.head.auth)
            val accounts = requireNotNull(rig.head.accountPool).view(null).accounts
            assertTrue(accounts.all { it.label.startsWith("native:") }, "no added Claude account")
            assertEquals(listOf(SPLICE_SELECTOR), accounts.filter { it.available }.map { it.label })
            HttpClient(Java).use { client ->
                val response = client.post("http://127.0.0.1:${rig.head.head.port}/v1/messages") {
                    bearerAuth("synthetic-caller")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"model":"synthetic-model","stream":false,"max_tokens":32,"messages":[{"role":"user","content":"synthetic native turn"}]}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            }
            assertEquals(listOf("Bearer synthetic-splice"), sent, "one native send, no added account or failover")
            assertInstanceOf(CredentialVerdict.Accepted::class.java, rig.head.auth.describe().verdict)
            assertArrayEquals(
                before,
                Files.readAllBytes(file),
                "only the native command may write or refresh its login",
            )
        } finally {
            rig.close()
            upstream.stop()
        }
    }
}
