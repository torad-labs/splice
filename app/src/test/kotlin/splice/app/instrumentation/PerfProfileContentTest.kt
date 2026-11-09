package splice.app.instrumentation

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.control.perf.PerfHttpWork
import splice.app.control.perf.PerfProfileContent
import splice.core.perf.runtime.PERF_PROFILE_READ_BUFFER_BYTES
import splice.core.perf.runtime.PerfProfileArtifact
import splice.core.perf.runtime.PerfProfileRead
import splice.core.util.PerfWorkBudget
import java.nio.file.Path

class PerfProfileContentTest {
    @Test
    fun `artifact response delivers all bytes before releasing its bounded reader`() = testApplication {
        val expected = ByteArray(PERF_PROFILE_READ_BUFFER_BYTES * 3 + 1) { (it % 251).toByte() }
        val before = PerfWorkBudget.retainedBytes()
        val work = PerfHttpWork()
        application {
            routing {
                get("/artifact") {
                    work.execute(call) {
                        PerfProfileContent(Reader(expected), work.io).use { content ->
                            call.respond(content)
                            content.awaitDelivered()
                        }
                    }
                }
            }
        }
        val response = client.get("/artifact")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(expected.size.toString(), response.headers["Content-Length"])
        assertArrayEquals(expected, response.body<ByteArray>())
        assertEquals(before, PerfWorkBudget.retainedBytes())
    }

    private class Reader(private val bytes: ByteArray) : PerfProfileRead {
        private val lease = checkNotNull(PerfWorkBudget.acquire(PERF_PROFILE_READ_BUFFER_BYTES.toLong()))
        private var closed = false
        private var offset = 0
        override val artifact =
            PerfProfileArtifact("fixture", Path.of("unused"), bytes.size.toLong(), "application/octet-stream")

        override fun read(buffer: ByteArray): Int {
            check(!closed) { "Artifact released before delivery finished" }
            if (offset == bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - offset)
            bytes.copyInto(buffer, 0, offset, offset + count)
            offset += count
            return count
        }

        override fun close() { closed = true; lease.close() }
    }
}
