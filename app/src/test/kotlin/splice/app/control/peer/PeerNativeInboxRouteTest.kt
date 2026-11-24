// Tests for `nativeInbox: true` peers (e.g. `splice desktop acp`): skips `peer-host` helper launch to prevent duplicate
// `ListAgents` entries, routes outbound sends through the owner process's own live session UDS socket, refuses
// self-sends to the owner session, and reports owner pid/socket in `GET /api/peers`.
package splice.app.control.peer

import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.api.ControlAudit
import splice.app.control.mount.ControlGuard
import splice.app.peer.PeerMailbox
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.failure.HeadVoice
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.head.admission.AdmissionErrorBody
import splice.http.ingress.HeapIngress
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import splice.upstream.memory.JvmHeap
import java.nio.file.Files
import java.nio.file.Path

class PeerNativeInboxRouteTest {
    private val log = LogSink { }

    private class RecordingHelpers : PeerHelpers {
        val started = mutableListOf<String>()

        override suspend fun start(name: String): Long? {
            started += name
            return 999L
        }

        override suspend fun stop(name: String) = Unit

        override fun reload(name: String) = Unit

        override fun running(name: String): RunningHelper? = null
    }

    private class RecordingOutbox : PeerOutbox {
        val sent = mutableListOf<Pair<SessionRecord, PeerSender>>()

        override suspend fun send(
            target: SessionRecord,
            text: String,
            sender: PeerSender,
            msgId: String?,
        ): SendOutcome {
            sent += target to sender
            return SendOutcome.Submitted(msgId ?: "msg-1")
        }

        override suspend fun receipt(
            toAddress: String,
            origMsgId: String,
            status: DeliveryStatus,
            detail: String?,
            sender: PeerSender,
        ): Boolean = false
    }

    @Test
    fun `nativeInbox requires a live owner, skips helper start, and sends via owner session socket`(
        @TempDir dir: Path,
    ) = testApplication {
        val self = checkNotNull(PeerOwnerProcesses().capture(ProcessHandle.current().pid()))
        val ownerSocket = "/tmp/cc-socks/owner-${self.pid}.sock"
        val targetSocket = "/tmp/cc-socks/target-42.sock"
        val records = listOf(
            sessionRecord("s-owner", null, self.pid, ownerSocket),
            sessionRecord("s-target", "walk-a", self.pid + 1, targetSocket),
        )
        val store = PeerStore(dir.resolve("settings"))
        val helpers = RecordingHelpers()
        val outbox = RecordingOutbox()
        val key = MgmtKey(StatePaths(baseOverride = dir.resolve("control")))
        val parts = PeerParts(
            store = store,
            registry = object : SessionSource {
                override fun read(): List<SessionRecord> = records

                override fun list(): SessionListing = SessionListing(records)
            },
            outbox = outbox,
            services = PeerServiceSource { PeerServices(helpers, PeerMailbox(store, dir.resolve("state"), log)) },
            log = log,
        )
        val mount = PeersMount(parts, guard(key, store))
        application { routing { mount.register(this) } }

        val noOwner = postPeer(client, key.get(), nativeBody("agy-no-owner", dir, null))
        assertEquals(HttpStatusCode.BadRequest, noOwner.first)

        val created = postPeer(client, key.get(), nativeBody("agy-live", dir, self))
        assertEquals(HttpStatusCode.Created, created.first, created.second)
        assertTrue(helpers.started.isEmpty(), "nativeInbox peer must not start a duplicate peer-host helper")
        assertTrue("agy-live" in store.nativeInboxes)
        assertTrue("agy-live" in PeerStore(dir.resolve("settings")).nativeInboxes)

        val listed = client.get("/api/peers") { header(HttpHeaders.Authorization, "Bearer ${key.get()}") }
        val peers = Json.parseToJsonElement(listed.bodyAsText()).jsonObject["peers"]?.jsonArray.orEmpty()
        val entry = peers.single().jsonObject
        assertEquals(true, entry["alive"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(ownerSocket, JsonScalars.str(entry, "socket"))

        val peerKey = Files.readString(store.keyFile("agy-live")).trim()
        val sendOk = sendAsPeer(client, peerKey, """{"to":"walk-a","text":"hello from agy"}""")
        assertEquals(HttpStatusCode.Accepted, sendOk.first, sendOk.second)
        assertEquals(PeerSender(ownerSocket, "agy-live"), outbox.sent.single().second)

        val selfSend = sendAsPeer(client, peerKey, """{"to":"s-owner","text":"loop"}""")
        assertEquals(HttpStatusCode.Conflict, selfSend.first, selfSend.second)
    }

    private fun nativeBody(
        name: String,
        dir: Path,
        owner: splice.sessions.peer.codex.NativeProcess?,
    ): String = buildJsonObject {
        put("name", name)
        put("cwd", dir.toString())
        put("nativeInbox", true)
        owner?.let {
            putJsonObject("owner") {
                put("pid", it.pid)
                put("procStart", it.procStart)
                put("pidDomain", it.pidDomain)
            }
        }
    }.toString()

    private suspend fun postPeer(
        client: io.ktor.client.HttpClient,
        mgmtKey: String,
        body: String,
    ): Pair<HttpStatusCode, String> {
        val reply = client.post("/api/peers") {
            header(HttpHeaders.Authorization, "Bearer $mgmtKey")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return reply.status to reply.bodyAsText()
    }

    private suspend fun sendAsPeer(
        client: io.ktor.client.HttpClient,
        peerKey: String,
        body: String,
    ): Pair<HttpStatusCode, String> {
        val reply = client.post("/api/peers/self/send") {
            header(HttpHeaders.Authorization, "Bearer $peerKey")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return reply.status to reply.bodyAsText()
    }

    private fun sessionRecord(id: String, name: String?, pid: Long, socket: String): SessionRecord = SessionRecord(
        sessionId = id,
        name = name,
        status = SessionStatus(state = "idle"),
        route = SessionRoute.Direct,
        availability = SessionAvailability.LIVE,
        process = SessionProcess(
            pid = pid,
            cwd = "/tmp",
            startedAt = 1L,
            updatedAt = 1L,
            messagingSocketPath = socket,
        ),
        client = SessionClient(kind = "interactive", version = "2.1.328", peerProtocol = 1),
    )

    private fun guard(key: MgmtKey, store: PeerStore) = ControlGuard(
        key,
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
}
