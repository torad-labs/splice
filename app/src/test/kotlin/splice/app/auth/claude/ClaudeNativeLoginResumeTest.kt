// external native login must resume its session without stale holds or sticky standby credentials.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ClaudeNativeLoginResumeTest {
    @TempDir
    lateinit var home: Path

    private val fixture by lazy { ClaudeNativePoolFixture(home) }

    private fun seed(place: String, expiresAt: Long = NATIVE_ACCESS_EXPIRY) = fixture.seed(place, expiresAt)

    private suspend fun rig(upstreamUrl: String): ClaudeNativePoolFixture.Rig = fixture.rig(upstreamUrl = upstreamUrl)

    private suspend fun nativeTurn(
        client: HttpClient,
        rig: ClaudeNativePoolFixture.Rig,
        session: String? = null,
        token: String = "synthetic-caller",
    ): HttpResponse =
        client.post("http://127.0.0.1:${rig.head.head.port}/v1/messages") {
            bearerAuth(token)
            session?.let { headers.append("x-claude-code-session-id", it) }
            contentType(ContentType.Application.Json)
            setBody(
                """{"model":"synthetic-model","stream":false,"max_tokens":32,"messages":[{"role":"user",""" +
                    """"content":"synthetic native turn"}]}""",
            )
        }

    @Test
    fun `in client login resumes the same session on its new credential without the old plan hold`() = runBlocking {
        seed("native")
        seed("splice", expiresAt = 1L)
        val sent = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val upstream = fixture.nativeUpstream(sent, bodies).start()
        val port = upstream.engine.resolvedConnectors().single().port
        val rig = rig(upstreamUrl = "http://127.0.0.1:$port")
        try {
            HttpClient(Java).use { client ->
                assertEquals(HttpStatusCode.TooManyRequests, nativeTurn(client, rig, "resumed-session").status)
                val file = fixture.replaceNative()
                val replacement = Files.readAllBytes(file)
                val resumed = nativeTurn(client, rig, "resumed-session")
                assertEquals(HttpStatusCode.OK, resumed.status, resumed.bodyAsText())
                assertEquals(listOf("Bearer synthetic-native", "Bearer synthetic-replacement"), sent)
                assertEquals(1, bodies.distinct().size, "login must not rewrite the resumed request")
                assertArrayEquals(replacement, Files.readAllBytes(file), "only native login writes credentials")
            }
        } finally {
            rig.close()
            upstream.stop()
        }
    }

    @Test
    fun `a new caller login replaces the resumed session's sticky standby`() = runBlocking {
        seed("native")
        seed("splice")
        val sent = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val upstream = fixture.nativeUpstream(sent, bodies).start()
        val port = upstream.engine.resolvedConnectors().single().port
        val rig = rig(upstreamUrl = "http://127.0.0.1:$port")
        try {
            HttpClient(Java).use { client ->
                val first = nativeTurn(client, rig, "resumed-session", "synthetic-native")
                assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
                assertEquals(listOf("Bearer synthetic-native", "Bearer synthetic-splice"), sent)
                assertEquals(HttpStatusCode.OK, nativeTurn(client, rig, "resumed-session", "synthetic-native").status)
                assertEquals("Bearer synthetic-splice", sent.last(), "unchanged caller login keeps failover sticky")
                fixture.replaceNative()
                val resumed = nativeTurn(client, rig, "resumed-session", "synthetic-replacement")
                assertEquals(HttpStatusCode.OK, resumed.status, resumed.bodyAsText())
                assertEquals("Bearer synthetic-replacement", sent.last(), "login must override sticky failover")
                assertEquals(1, bodies.distinct().size)
            }
        } finally {
            rig.close()
            upstream.stop()
        }
    }
}
