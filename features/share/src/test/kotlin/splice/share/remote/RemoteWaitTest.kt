// NEW: LAN share, round 18 of PR #419's review. The goal: a machine this one joined that does not answer is reported
// honestly, and the wait the report names is the wait that ran out. A machine that never makes the connection (off,
// asleep, gone from the network, or something on its port that never speaks TLS) is reported once the wait for a
// connection ran out, and one that makes it and then says nothing once the wait for that request's answer ran out,
// which for a collection of notes is longer than for a read. Read through the real client, against a local port that
// takes the connection and never speaks, and a stand-in sharing machine that makes it and never answers.
package splice.share.remote

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.share.ShareCertificate
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.math.abs

// why: the machine the reads go to.
private const val NAME = "torad"

// why: the pin of a machine whose certificate is never reached.
private val PIN = "sha256:" + "0".repeat(64)

// why: how far the wait a report names may be from the time it took: a loaded machine runs a timer late.
private const val SLACK_MS = 2_000L

class RemoteWaitTest {
    @TempDir
    lateinit var tmp: Path

    /** What [read] on the machine pinned as [pin] reports when it gets no answer, and how long it took to report it, in
     *  milliseconds. */
    private fun unanswered(pin: String, read: (RemoteApi) -> Unit): Pair<String, Long> {
        val store = RemotesStore(tmp.resolve("remotes.json"))
        store.save(NAME, "a-token")
        val api = RemoteApi(NAME, RemoteTrust(pin, NAME), store)
        val started = System.nanoTime()
        val said = assertThrows<RemoteUnanswered> { read(api) }.said
        return said to (System.nanoTime() - started) / 1_000_000
    }

    /** The report [said] and the time it took, [waited], agree: it names a wait, and that wait is the one that ran
     *  out. */
    private fun agree(said: String, waited: Long) {
        val seconds = Regex("within (\\d+) s").find(said)?.groupValues?.get(1)?.toLong()
        assertNotNull(seconds, "the report names the wait: $said")
        val named = checkNotNull(seconds) * 1_000
        assertTrue(abs(waited - named) < SLACK_MS, "it waited $waited ms, and says: $said")
    }

    @Test
    fun `a machine that never makes the connection is reported with the wait for a connection`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            val (said, waited) = unanswered(PIN) { it.health("https://127.0.0.1:${silent.localPort}") }
            agree(said, waited)
        }
    }

    @Test
    fun `a machine that makes the connection and never answers a collection is reported with the collection's wait`() {
        val tls = ShareCertificate(tmp.resolve("tls.pem")).load(listOf("localhost"))
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(tls.keyStore, tls.password) }
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        server.httpsConfigurator = HttpsConfigurator(context)
        val released = CountDownLatch(1)
        server.createContext("/share/v1/sessions/outbox") { released.await() }
        server.start()
        try {
            val url = "https://127.0.0.1:${server.address.port}"
            val (said, waited) = unanswered(tls.fingerprint) { it.outbox(url, null) }
            agree(said, waited)
        } finally {
            released.countDown()
            server.stop(0)
        }
    }
}
