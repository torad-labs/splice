// Goal: a request that reached Meta whole may still be generating there, so a turn that ends without hearing Meta
// finish it moves its conversation to a new route. That holds when the request's last bytes go out after the turn
// ended: the client left while they were going out, and the transport hears they went out only once the turn is over.
// The head cannot place that moment in a test, so this drives the route keeper as the transport and the head call it.
package splice.provider.muse

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import splice.core.util.LogSink

private const val CONVERSATION = "muse:sess-left-mid-write"
private const val ROUTE_HEADER = "x-meta-ai-gateway-session-id"

class MuseGatewayRoutesTest {
    @Test
    fun `a request written whole after its turn ended moves its conversation to a new route`() {
        val routes = MuseGatewayRoutes(null, LogSink { })
        val turn = routes.route(CONVERSATION)
        val sent = turn.sending().getValue(ROUTE_HEADER)

        turn.turnEnded()
        turn.written()

        assertNotEquals(sent, routes.headers(CONVERSATION).getValue(ROUTE_HEADER), "the next request leaves $sent")
    }
}
