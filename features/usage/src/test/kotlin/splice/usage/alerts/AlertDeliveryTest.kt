// NEW: V4-133 review — a reached `warn` budget reaches the webhook the operator saved.
//
// A real HTTP endpoint on a bound port 0 stands in for the operator's webhook: the assertions are on the
// bytes it RECEIVED, in the shape the route's own test send posts, not on a call having been made.
package splice.usage.alerts

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path

private const val DELIVERY_WAIT_MS = 10_000L

/** The operator's webhook: records the first body it receives and answers [status]. */
private class Webhook(private val status: Int = 200) {
    val received = CompletableDeferred<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}/hook"

    init {
        server.createContext("/hook") { exchange ->
            received.complete(exchange.requestBody.readBytes().decodeToString())
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
    }

    fun stop() {
        server.stop(0)
    }
}

class AlertDeliveryTest {

    @Test
    fun `a reached budget posts its text to the saved webhook in the test send's shape`(@TempDir tmp: Path) =
        runBlocking {
            val webhook = Webhook()
            try {
                val store = AlertStore(tmp.resolve("alerts.json"))
                store.replace(AlertSettings(webhookUrl = webhook.url))
                AlertDelivery(store, { }, this).budgetReached("h", "head 'h' reached its daily budget")

                val body = withTimeout(DELIVERY_WAIT_MS) { webhook.received.await() }
                val text = Json.parseToJsonElement(body).jsonObject.getValue("text").jsonPrimitive.content
                assertEquals("head 'h' reached its daily budget", text)
            } finally {
                webhook.stop()
            }
        }

    @Test
    fun `with no webhook saved nothing is launched`(@TempDir tmp: Path) {
        val job = SupervisorJob()
        AlertDelivery(AlertStore(tmp.resolve("alerts.json")), { }, CoroutineScope(job)).budgetReached("h", "x")
        assertEquals(0, job.children.count(), "no saved URL: no delivery to launch")
        job.cancel()
    }

    @Test
    fun `a webhook that refuses the alert is named in the head's log`(@TempDir tmp: Path) = runBlocking {
        val webhook = Webhook(status = 500)
        val logged = CompletableDeferred<String>()
        try {
            val store = AlertStore(tmp.resolve("alerts.json"))
            store.replace(AlertSettings(webhookUrl = webhook.url))
            AlertDelivery(store, { logged.complete(it) }, this).budgetReached("h", "x")

            val line = withTimeout(DELIVERY_WAIT_MS) { logged.await() }
            assertTrue(line.startsWith("[h][budget]") && line.contains("500"), line)
        } finally {
            webhook.stop()
        }
    }
}
