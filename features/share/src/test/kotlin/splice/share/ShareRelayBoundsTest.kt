// NEW: PR 247 review, finding "the relay buffers uploads before the head's size and read-time limits can apply".
// What a joined machine hears when its body is past the offered head's own maxRequestBytes: the head's own 413,
// word for word, answered by the sharing listener before the body is held whole or reaches the head.
package splice.share

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.content.OutgoingContent
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.head.admission.AdmissionErrorBody
import java.nio.file.Path

// why: the offered head's own cap, set as its [heads.h.overrides] maxRequestBytes; small, so a test body passes it.
private const val HEAD_CAP = 64

// why: a port nothing listens on; a body the relay wrongly let through would be answered 502, not 413.
private const val NO_HEAD_PORT = 1

class ShareRelayBoundsTest {
    @TempDir
    lateinit var tmp: Path

    private val tooLarge by lazy {
        AdmissionErrorBody.render("invalid_request_error", "request body exceeds $HEAD_CAP bytes")
    }

    private fun relay(): ShareRelay {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val config = ConfigService(paths, perHeadOverrides = mapOf("h" to mapOf("maxRequestBytes" to "$HEAD_CAP")))
        return ShareRelay(TurnKey(MgmtKey(paths)), config, Dispatchers.IO, LogSink {})
    }

    @Test
    fun `a body declared past the head's cap gets the head's own 413`() = testApplication {
        val relay = relay()
        routing { post("/turn") { relay.relay(call, ShareTarget("h", NO_HEAD_PORT, "/v1/messages", "test")) } }
        val response = client.post("/turn") { setBody("x".repeat(HEAD_CAP + 1)) }
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, response.status.value)
        assertEquals(tooLarge, response.bodyAsText())
    }

    @Test
    fun `a body of no declared length is refused the same once it passes the cap`() = testApplication {
        val relay = relay()
        routing { post("/turn") { relay.relay(call, ShareTarget("h", NO_HEAD_PORT, "/v1/messages", "test")) } }
        val undeclared = object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(ByteArray(HEAD_CAP + 1))
            }
        }
        val response = client.post("/turn") { setBody(undeclared) }
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, response.status.value)
        assertEquals(tooLarge, response.bodyAsText())
    }
}
