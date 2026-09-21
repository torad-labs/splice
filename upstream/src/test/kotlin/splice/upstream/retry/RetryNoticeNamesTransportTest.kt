// NEW: V4-167 — a transport retry's log line names the failure the way the turn's ending does. The
// ending learned the words in V4-164; the per-attempt line kept Throwable.message, which the JDK
// client leaves null for a refused connect, so daemon.log read "ConnectException attempt 2/10: ".
package splice.upstream.retry

import clientOver
import fakeAuth
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import posted
import splice.upstream.RetryNotice
import splice.upstream.transport.PostContext
import java.net.ConnectException
import java.nio.channels.ClosedChannelException

class RetryNoticeNamesTransportTest {

    // Mutant: the attempt line prints e.message again. Every notice ends in an empty detail.
    @Test
    fun `a refused connect's retry line says it was refused, and where`() = runTest {
        val notices = mutableListOf<String>()
        val engine = MockEngine { throw ConnectException().apply { initCause(ClosedChannelException()) } }
        val ctx = PostContext(
            url = "http://127.0.0.1:8099/v1/chat/completions",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            onRetry = RetryNotice { notices += it },
        )
        assertThrows<Exception> { clientOver(engine).posted(ctx, "{}") { "ok" } }

        val attempts = notices.filter { "attempt" in it }
        assertTrue(attempts.isNotEmpty(), "$notices")
        assertTrue(attempts.all { "connection refused by 127.0.0.1:8099" in it }, "$attempts")
    }
}
