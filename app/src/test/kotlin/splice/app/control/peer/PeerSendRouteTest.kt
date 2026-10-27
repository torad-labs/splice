// Oct 27, 2026 (PR #247 review): POST /api/peers/self/send under a caller's id, as the daemon mounts it, over a real
// peer store, session registry, helper record and outbox log. The one stand-in is the inbox of the Claude Code session
// the sends go to, where a frame leaves the daemon. bb3aeab25 made a caller's id safe to repeat ("repeated ids answer
// their first receipt"), and that holds for repeats that arrive together: the session, which keeps no ids, shows the
// message once, and every caller is answered with that one send, the later ones as its repeat.
package splice.app.control.peer

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.api.ControlAudit
import splice.app.control.mount.ControlGuard
import splice.app.peer.PeerMailbox
import splice.app.peer.PeerPlaces
import splice.app.peer.PeerSupervisor
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.failure.HeadVoice
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.head.admission.AdmissionErrorBody
import splice.http.ingress.HeapIngress
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.helper.HelperFile
import splice.sessions.peer.helper.HelperFiles
import splice.sessions.peer.helper.PeerStateFiles
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.memory.JvmHeap
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

private const val PEER = "temple"
private const val SEAT = "0a8e7c2b-5d14-4f3a-9b61-2c7e8d9f0a1b"
private const val SEAT_NAME = "walk-a"
private const val ID = "tell-7"
private const val TEXT = "the train landed"

/** Sends under one id that arrive together: inside a peer's burst of 20, so the rate refuses none of them. */
private const val CALLERS = 8

/** How long the session's inbox holds a frame that no second frame follows. A send that nothing holds back reaches
 *  the inbox well inside this, so the inbox stays quiet this long only for a send that waits its turn. */
private const val HOLD_MS = 1_000L

/** One frame the session took: whose inbox it reached, the msg_id it carried, and the text the session shows. */
private data class Frame(val sessionId: String?, val msgId: String?, val text: String)

/** What one caller was answered: the status, the msgId, the session it names, and whether it repeats a send. */
private data class Answer(val status: HttpStatusCode, val msgId: String?, val sessionId: String?, val repeat: Boolean)

/**
 * The inbox of the Claude Code session the sends go to, where a frame leaves the daemon: the one stand-in here. A
 * Claude Code session keeps no ids, so every frame it takes is one more message on its screen. It is slow to take one,
 * as a session busy in a turn is: a frame waits until a second frame reaches the inbox or [HOLD_MS] runs out, so a
 * second send under the id that nothing holds back always arrives while the first is still on its way.
 */
private class SessionInbox : PeerOutbox {
    val frames = ConcurrentLinkedQueue<Frame>()
    private val second = CompletableDeferred<Unit>()

    override suspend fun send(target: SessionRecord, text: String, sender: PeerSender, msgId: String?): SendOutcome {
        frames.add(Frame(target.sessionId, msgId, text))
        if (frames.size > 1) second.complete(Unit)
        withTimeoutOrNull(HOLD_MS) { second.await() }
        return SendOutcome.Submitted(msgId ?: UUID.randomUUID().toString())
    }

    /** No send here answers a message, so no receipt goes out. */
    override suspend fun receipt(
        toAddress: String,
        origMsgId: String,
        status: DeliveryStatus,
        detail: String?,
        sender: PeerSender,
    ): Boolean = false
}

class PeerSendRouteTest {
    private val log = LogSink { }

    @Test
    fun `sends under one id that arrive together reach the session once, and every caller gets that send`(
        @TempDir dir: Path,
    ) = testApplication {
        val store = PeerStore(dir.resolve("settings"))
        val registered = store.register(PeerRequest(PEER, dir.toString(), listOf("*")), emptySet())
        val keyFile = assertInstanceOf(PeerRegistration.Registered::class.java, registered).keyFile
        val key = Files.readString(keyFile).trim()
        val inbox = SessionInbox()
        val mount = PeersMount(parts(dir, store, inbox), guard(dir, store))
        application { routing { mount.register(this) } }

        val answers = coroutineScope { List(CALLERS) { async { send(client, key) } }.awaitAll() }

        assertEquals(listOf(Frame(SEAT, ID, TEXT)), inbox.frames.toList(), "the session shows the message once")
        val first = Answer(HttpStatusCode.Accepted, ID, SEAT, repeat = false)
        val repeat = Answer(HttpStatusCode.OK, ID, SEAT, repeat = true)
        assertEquals(mapOf(first to 1, repeat to CALLERS - 1), answers.groupingBy { it }.eachCount(), "$answers")
    }

    /** The routes' parts as the daemon wires them, with [inbox] where frames leave: the peer's helper.json names this
     *  process as its running helper, and the session is the one registration in the sessions folder. */
    private fun parts(dir: Path, store: PeerStore, inbox: PeerOutbox): PeerParts {
        val state = dir.resolve("state")
        val sessions = Files.createDirectories(dir.resolve("sessions"))
        val pid = ProcessHandle.current().pid()
        Files.writeString(sessions.resolve("$pid.json"), registration(pid, dir.resolve("cc-socks/$pid.sock")))
        val helpers = HelperFiles()
        val me = helpers.self(now = 0L)
        val helper = HelperFile(me.pid, me.procStart, "$dir/run/$PEER.sock", me.startedAt, version = "test")
        helpers.write(PeerStateFiles(state, PEER), helper)
        val places = PeerPlaces(dir.resolve("settings"), state, listOf(sessions), runtimeDir = null)
        val supervisor = PeerSupervisor(store, places, EnvReader { null }, ProcessDispatchers().io(), log)
        val services = PeerServices(supervisor, PeerMailbox(store, state, log))
        val registry = SessionRegistry(sessions, RouteOfPid { SessionRoute.Unknown })
        return PeerParts(store, registry, inbox, PeerServiceSource { services }, log)
    }

    /** The session's registration as Claude Code writes one: a session, not a peer, so it keeps no ids. */
    private fun registration(pid: Long, socket: Path): String = buildJsonObject {
        put("pid", pid)
        put("sessionId", SEAT)
        put("name", SEAT_NAME)
        put("kind", "interactive")
        put("version", "2.1.310")
        put("peerProtocol", 1)
        put("messagingSocketPath", socket.toString())
    }.toString()

    /** The guard ControlPlane builds, with [store]'s peers admitted at the peer door. */
    private fun guard(dir: Path, store: PeerStore) = ControlGuard(
        MgmtKey(StatePaths(baseOverride = dir.resolve("control"))),
        ControlAudit(log),
        log,
        HeapIngress(
            JvmHeap.budget,
            { Knob.MAX_REQUEST_BYTES.count().toInt() },
            AdmissionErrorBody(HeadVoice("splice", loginCommand = null)),
            readIdleMs = { Knob.INGRESS_READ_IDLE_MS.count() },
        ),
        peers = store,
    )

    /** One caller's `splice peer send --id`: [TEXT] to the session under [ID], and what it was answered. */
    private suspend fun send(client: HttpClient, key: String): Answer {
        val reply = client.post("/api/peers/self/send") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody("""{"to":"$SEAT_NAME","text":"$TEXT","id":"$ID"}""")
        }
        val body = Json.parseToJsonElement(reply.bodyAsText()).jsonObject
        val to = JsonScalars.str(body["to"] as? JsonObject, "sessionId")
        return Answer(reply.status, JsonScalars.str(body, "msgId"), to, JsonScalars.str(body, "repeat") == "true")
    }
}
