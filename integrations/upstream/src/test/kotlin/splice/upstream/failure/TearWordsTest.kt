// NEW: V4-242 (2026-09-26) — a tear's words: the deepest link of its cause chain that says anything,
// within the chain's own bound, with any URL cut to its scheme and host.
package campaign.v4242

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.upstream.failure.TearWords
import java.io.IOException
import java.net.ConnectException

class TearWordsTest {
    @Test
    fun `a tear over a peer's close reads as the close`() {
        val tear = IOException("websocket stream ended mid-round", IOException(CLOSE))
        assertEquals(CLOSE, TearWords.of(tear))
    }

    @Test
    fun `a link with blank or no words is passed over`() {
        val tear = IOException("websocket stream ended mid-round", IOException("  ", ConnectException()))
        assertEquals("websocket stream ended mid-round", TearWords.of(tear))
    }

    @Test
    fun `a tear no link of which says anything has no words`() {
        assertNull(TearWords.of(IOException(null as String?, ConnectException())))
    }

    @Test
    fun `words past the chain's bound are never read, and a cyclic chain still ends`() {
        val deep = (1..DEEPER_THAN_THE_BOUND).fold<Int, Throwable>(IOException("the deepest")) { cause, _ ->
            IOException(null as String?, cause)
        }
        assertEquals("the top", TearWords.of(IOException("the top", deep)))

        val a = IOException("a")
        val b = IOException("b")
        a.initCause(b)
        b.initCause(a)
        assertEquals("b", TearWords.of(a), "eight links of a, b, a, b: the last that spoke is b")
    }

    @Test
    fun `a URL keeps its scheme and host, never its path or query`() {
        val timeout = IOException(
            "Request timeout has expired [url=https://api.example.test/v1/responses?key=sk-fake-v4242, " +
                "request_timeout=900000 ms]",
        )
        assertEquals(
            "Request timeout has expired [url=https://api.example.test, request_timeout=900000 ms]",
            TearWords.of(timeout),
        )
        assertEquals(
            "failed to reach wss://chatgpt.test",
            TearWords.of(IOException("failed to reach wss://chatgpt.test/backend-api/codex/responses")),
        )
    }
}

private const val CLOSE =
    "socket closed by the peer (status=1011, no reason given) after 2 events " +
        "(codex.rate_limits, codex.response.metadata)"

// FailureChain reads eight links; the deepest words sit twelve below the top.
private const val DEEPER_THAN_THE_BOUND = 12
