// Oct 28, 2026: POST /api/peers and POST /api/peers/codex, as the daemon mounts them, over a real peer store.
// A register that repeats one already there answers it with 200 and the same session id and key file, where it
// was refused.
// POST /api/peers/codex: a Codex home where no app-server listens refuses every attach, a thread its Codex process
// hosts included, since no message could reach it. Rewritten Oct 28, 2026, when Marcos dropped the hook route that
// had let a `--no-daemon` thread attach anyway.
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.api.ControlAudit
import splice.app.control.mount.ControlGuard
import splice.app.peer.PeerMailbox
import splice.app.peer.codex.CodexStandInAppServer
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

class PeerRegistrationRoutesTest {
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
    fun `no thread attaches where no app-server listens, even one its Codex process hosts`(
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
        assertEquals(HttpStatusCode.Conflict, hosted.first, hosted.second)
        assertNull(store.get("codex-$THREAD"), "a thread only its own process holds is not registered")
    }

    // Oct 30, 2026: a thread run by a Codex that is not its home's shared app-server (the ChatGPT desktop app's own, on
    // this machine) attached with 201, its helper read "the app-server has not loaded the thread" and ended, and the
    // daemon revoked the peer 20 s later: the caller was told it had a peer, and no message could ever reach it.
    @Test
    fun `a thread the home's app-server has not loaded is refused, and one it has loaded attaches`(
        @TempDir dir: Path,
    ) = testApplication {
        val store = PeerStore(dir.resolve("settings"))
        val key = MgmtKey(StatePaths(baseOverride = dir.resolve("control")))
        val mount = PeersMount(parts(dir, store), guard(key, store))
        application { routing { mount.register(this) } }
        val home = dir.resolve("codex-home")
        val socket = home.resolve("app-server-control").resolve("app-server-control.sock")

        val elsewhere = CodexStandInAppServer(socket, THREAD, status = "notLoaded").use {
            attach(client, key.get(), body(home.toString(), dir.toString(), owner = owner()))
        }
        assertEquals(HttpStatusCode.Conflict, elsewhere.first, elsewhere.second)
        assertTrue("has not loaded the thread" in elsewhere.second, elsewhere.second)
        assertNull(store.get("codex-$THREAD"), "a thread no message could reach is not registered")

        Files.delete(socket)
        val loaded = CodexStandInAppServer(socket, THREAD).use {
            attach(client, key.get(), body(home.toString(), dir.toString(), owner = owner()))
        }
        assertEquals(HttpStatusCode.Created, loaded.first, loaded.second)
        assertNotNull(store.get("codex-$THREAD"), "a thread its app-server holds is a peer")
    }

    @Test
    fun `a register that repeats one answers 200 with the same peer, and the first answers 201`(
        @TempDir dir: Path,
    ) = testApplication {
        val store = PeerStore(dir.resolve("settings"))
        val key = MgmtKey(StatePaths(baseOverride = dir.resolve("control")))
        val mount = PeersMount(parts(dir, store), guard(key, store))
        application { routing { mount.register(this) } }
        val body = buildJsonObject {
            put("name", "grokbot")
            put("cwd", dir.toString())
        }.toString()

        val first = post(client, key.get(), "/api/peers", body)
        assertEquals(HttpStatusCode.Created, first.first, first.second)
        val again = post(client, key.get(), "/api/peers", body)
        assertEquals(HttpStatusCode.OK, again.first, again.second)
        val session = { reply: String -> JsonScalars.str(JsonScalars.objectOrNull(Json, reply), "sessionId") }
        assertEquals(session(first.second), session(again.second))
        val keyFile = JsonScalars.str(JsonScalars.objectOrNull(Json, again.second), "keyFile")
        assertEquals(store.keyFile("grokbot").toString(), keyFile)
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

    private suspend fun attach(client: HttpClient, key: String, body: String): Pair<HttpStatusCode, String> =
        post(client, key, "/api/peers/codex", body)

    private suspend fun post(
        client: HttpClient,
        key: String,
        path: String,
        body: String,
    ): Pair<HttpStatusCode, String> {
        val reply = client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return reply.status to reply.bodyAsText()
    }
}
