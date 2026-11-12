package splice.app.control.peer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.WallClock
import splice.sessions.peer.journal.MessageDigests
import splice.sessions.peer.store.PeerLimit
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionStatus

private const val SEAT = "0a8e7c2b-5d14-4f3a-9b61-2c7e8d9f0a1b"

class PeerSendRequestsTest {
    private val text = "the train landed"

    private fun request(id: String? = "tell-7", pid: Long? = null, procStart: String? = null) =
        PeerSendRequests.of(
            buildJsonObject {
                id?.let { put("id", it) }
                pid?.let { put("pid", it) }
                procStart?.let { put("procStart", it) }
            },
            text,
        )

    private fun prior(outcome: String, digest: String? = MessageDigests.of(text)) =
        PriorSend(outcome, digest, SEAT, "walk-a", "uds:/run/x/42.sock")

    private fun seat(pid: Long = 42L, start: String = "900", kind: String? = "interactive") = SessionRecord(
        sessionId = SEAT,
        name = "walk-a",
        status = SessionStatus("idle"),
        route = SessionRoute.Direct,
        availability = SessionAvailability.LIVE,
        process = SessionProcess(pid, "/w", 1L, 1L, "/run/x/42.sock", procStart = start),
        client = SessionClient(kind, "2.1.0", 1),
    )

    @Test
    fun `an id that already went to the same target with the same text answers that send and sends nothing`() {
        assertEquals(IdTurn.Repeat, PeerSendRequests.turn(prior("submitted"), request(), "walk-a"))
        assertEquals(IdTurn.Repeat, PeerSendRequests.turn(prior("submitted"), request(), SEAT.take(8)))
        assertEquals(IdTurn.Fresh, PeerSendRequests.turn(null, request(), "walk-a"))
    }

    @Test
    fun `another text or another target under an id already used is refused`() {
        val changed = PeerSendRequests.turn(prior("submitted", "0".repeat(64)), request(), "walk-a")
        val elsewhere = PeerSendRequests.turn(prior("submitted"), request(), "walk-b")
        val unrecorded = PeerSendRequests.turn(prior("submitted", null), request(), "walk-a")

        assertTrue(changed is IdTurn.Taken, "$changed")
        assertTrue(elsewhere is IdTurn.Taken, "$elsewhere")
        assertTrue(unrecorded is IdTurn.Taken, "a send recorded without its text's digest cannot be matched")
    }

    @Test
    fun `an id refused before it left goes again, and one that may have arrived goes only where ids are kept`() {
        assertEquals(IdTurn.Fresh, PeerSendRequests.turn(prior("refused"), request(), "walk-a"))
        val failed = PeerSendRequests.turn(prior("failed"), request(), "walk-a")
        assertEquals(IdTurn.Unconfirmed, failed)

        assertNotNull(PeerSendRequests.targetRefusal(seat(), request(), failed), "a Claude Code session keeps no ids")
        assertNull(PeerSendRequests.targetRefusal(seat(kind = "splice-peer"), request(), failed))
    }

    @Test
    fun `a send pinned to a process is refused when another process now answers to its target`() {
        val pinned = request(pid = 42L, procStart = "900")

        assertNull(PeerSendRequests.targetRefusal(seat(), pinned, IdTurn.Fresh))
        assertNotNull(PeerSendRequests.targetRefusal(seat(pid = 43L), pinned, IdTurn.Fresh))
        assertNotNull(PeerSendRequests.targetRefusal(seat(start = "901"), pinned, IdTurn.Fresh), "a reused pid")
        assertNull(PeerSendRequests.targetRefusal(seat(start = "901"), request(pid = 42L), IdTurn.Fresh))
    }

    @Test
    fun `a malformed id or pin is refused before any target is read`() {
        val bad = buildJsonObject { put("id", "has space") }
        val startAlone = buildJsonObject { put("procStart", "900") }

        assertNotNull(PeerSendRequests.of(bad, text).refusal(bad))
        assertNotNull(PeerSendRequests.of(startAlone, text).refusal(startAlone))
        assertNull(request().refusal(buildJsonObject { put("id", "tell-7") }))
    }

    @Test
    fun `a peer's own limit sets its burst and its refill`() {
        var now = 0L
        val rate = PeerSendRate(WallClock { now })
        val limit = PeerLimit(perMinute = 60, burst = 3)

        repeat(3) { assertEquals(SendAllowance.Go, rate.take("temple", limit)) }
        assertEquals(SendAllowance.Wait(1), rate.take("temple", limit))
        now += 1_000
        assertEquals(SendAllowance.Go, rate.take("temple", limit))
        repeat(20) { assertEquals(SendAllowance.Go, rate.take("tell", PeerLimit())) }
    }
}
