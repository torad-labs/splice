// Oct 28, 2026: POST /api/peers/codex, as the daemon mounts it, over a real peer store. A Codex home where no
// app-server listens used to refuse every attach, which kept a thread that `codex resume --no-daemon` serves in its own
// process off the registry. A thread hosted by a Codex process that runs is attached anyway, since its messages reach
// it at its next hook; a thread with no such process and no app-server is still refused, since nothing could reach it.
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.api.ControlAudit
import splice.app.control.mount.ControlGuard
import splice.app.peer.PeerMailbox
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.failure.HeadVoice
import splice.core.util.LogSink
import splice.head.admission.AdmissionErrorBody
import splice.http.ingress.HeapIngress
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.upstream.memory.JvmHeap
import java.nio.file.Files
import java.nio.file.Path

private const val THREAD = "019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e5f"

// why: the stat field after the command name that holds a process's kernel start (field 22 of /proc/<pid>/stat).
private const val START_FIELD = 19

class PeerCodexRouteTest {
    private val log = LogSink { }

    /** No helper starts here: the route's answer is what this checks, not the helper's life. */
    private class NoHelpers : PeerHelpers {
        override suspend fun start(name: String): Long? = null

        override suspend fun stop(name: String) = Unit

        override fun reload(name: String) = Unit

        override fun running(name: String): RunningHelper? = null
    }

    /** No message leaves the daemon here. */
    private class NoOutbox : PeerOutbox {
        override suspend fun send(target: SessionRecord, text: String, sender: PeerSender, msgId: String?) =
            SendOutcome.Submitted("unused")

        override suspend fun receipt(
            toAddress: String,
            origMsgId: String,
            status: DeliveryStatus,
            detail: String?,
            sender: PeerSender,
        ): Boolean = false
    }

    @Test
    fun `a thread its Codex process hosts attaches where no app-server listens, and one with no process does not`(
        @TempDir dir: Path,
    ) = testApplication {
        val store = PeerStore(dir.resolve("settings"))
        val key = MgmtKey(StatePaths(baseOverride = dir.resolve("control")))
        val mount = PeersMount(parts(dir, store), guard(key, store))
        application { routing { mount.register(this) } }
        val home = dir.resolve("codex-home").toString()

        val cwd = dir.toString()
        val orphan = attach(client, key.get(), body(home, cwd, owner = null))
        assertEquals(HttpStatusCode.Conflict, orphan.first, orphan.second)
        assertNull(store.get("codex-$THREAD"), "a thread nothing could reach is not registered")

        val hosted = attach(client, key.get(), body(home, cwd, owner = owner()))
        assertEquals(HttpStatusCode.Created, hosted.first, hosted.second)
        assertNotNull(store.get("codex-$THREAD"), "the thread is the peer codex-<thread>")
    }

    private fun parts(dir: Path, store: PeerStore): PeerParts {
        val sessions = Files.createDirectories(dir.resolve("sessions"))
        val services = PeerServices(NoHelpers(), PeerMailbox(store, dir.resolve("state"), log))
        val registry = SessionRegistry(sessions, RouteOfPid { SessionRoute.Unknown })
        return PeerParts(store, registry, NoOutbox(), PeerServiceSource { services }, log)
    }

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

    /** The Gradle worker's parent stands for the Codex process that hosts the thread: a live process of this user. */
    private fun owner(): JsonObject {
        val pid = ProcessHandle.current().parent().orElseThrow().pid()
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        val start = stat.substringAfterLast(')').trim().split(' ')[START_FIELD]
        val machine = Files.readString(Path.of("/etc/machine-id")).trim()
        val namespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString()
        return buildJsonObject {
            put("pid", pid)
            put("procStart", start)
            put("pidDomain", "linux:$machine:$namespace")
        }
    }

    private fun body(home: String, cwd: String, owner: JsonObject?): String = buildJsonObject {
        put("thread", THREAD)
        put("home", home)
        put("cwd", cwd)
        if (owner != null) putJsonObject("owner") { owner.forEach { (name, value) -> put(name, value) } }
    }.toString()

    private suspend fun attach(client: HttpClient, key: String, body: String): Pair<HttpStatusCode, String> {
        val reply = client.post("/api/peers/codex") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return reply.status to reply.bodyAsText()
    }
}
