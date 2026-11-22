// Server mode P3 (docs/specs/server-mode.md, F14): durable state snapshots & fail-closed startup restore.
//   - Snapshots keys, people, budgets, ledger, and audit log to folder or S3-compatible bucket.
//   - Restores automatically on a fresh empty `<state>/server` folder so existing keys and settings survive disk loss.
//   - Fails closed on startup when local state is empty and the backup store is unreachable or corrupt.
package splice.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerBackup
import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class ServerP3DurableStateTest {
    @TempDir lateinit var work: Path

    private val http = HttpClient.newHttpClient()
    private val clock = SetClock()
    private val rig = ServerRig(LogSink {}, clock)

    @AfterEach
    fun stop() {
        rig.close()
    }

    @Test
    fun `folder backup snapshots state and restores keys, people, budgets and audit on a fresh disk`() = runBlocking {
        val backupDir = Files.createDirectories(work.resolve("backups"))
        val stateA = work.resolve("disk-a")
        val config = rig.config.copy(
            admins = listOf("ops@team.example"),
            backup = ServerBackup(to = "file://$backupDir", every = "1h"),
        )
        val first = rig.open(stateA, listOf(StandInHead(1)), config)
        val opsKey = rig.keyFor("ops@team.example")
        val adaKey = rig.keyFor("ada@team.example")
        first.personApi.set("ada@team.example", buildJsonObject { put("team", "core") })
        first.books.budgetApi.set(
            buildJsonObject {
                put("scope", "team")
                put("name", "core")
                put("daily_usd", 40.0)
            },
        )

        val snap = send("POST", "/server/api/backup", "x-api-key" to opsKey)
        assertEquals(200, snap.statusCode(), snap.body())
        assertTrue(Files.isRegularFile(backupDir.resolve("splice-state/latest.json")))
        assertTrue(Files.isRegularFile(backupDir.resolve("splice-state/2026/11/15/12.json")))
        rig.close()

        val stateB = work.resolve("disk-b")
        val restored = rig.open(stateB, listOf(StandInHead(1)), config)
        assertEquals(200, send("GET", "/server/api/keys", "x-api-key" to opsKey).statusCode())
        assertEquals(200, send("GET", "/", "x-api-key" to adaKey).statusCode())
        val audit = restored.audit.list() as ServerAnswer.Done
        assertTrue(audit.body["entries"]!!.jsonArray.isNotEmpty(), "restored audit log should retain entries")
    }

    @Test
    fun `fresh disk fails closed when backup target is unreachable so server never starts empty over a backup`() {
        val missingBackup = work.resolve("unmounted-backup-volume")
        val freshDisk = work.resolve("fresh-disk")
        val config = rig.config.copy(backup = ServerBackup(to = "file://$missingBackup", every = "1h"))
        val failure = assertThrows(IllegalStateException::class.java) {
            rig.open(freshDisk, listOf(StandInHead(1)), config)
        }
        assertTrue("refusing to start empty" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `s3 backup signs PUT and GET requests, restores on fresh disk, and fails closed on 500`() = runBlocking {
        val failReads = AtomicBoolean(false)
        val s3 = startMockS3(ConcurrentHashMap(), failReads)
        try {
            val env = EnvReader { name -> mapOf("BK_KEY" to "access-test", "BK_SECRET" to "secret-test")[name] }
            val config = rig.config.copy(
                admins = listOf("ops@team.example"),
                backup = ServerBackup(
                    to = "s3://team-state/prod",
                    endpoint = "http://127.0.0.1:${s3.address.port}",
                    region = "us-east-1",
                    pathStyle = true,
                    accessKeyEnv = "BK_KEY",
                    secretKeyEnv = "BK_SECRET",
                ),
            )
            rig.open(work.resolve("s3-disk-a"), listOf(StandInHead(1)), config, env = env)
            val opsKey = rig.keyFor("ops@team.example")
            val snap = send("POST", "/server/api/backup", "x-api-key" to opsKey)
            assertEquals(200, snap.statusCode(), snap.body())
            assertTrue(Json.parseToJsonElement(snap.body()).jsonObject["files"] != null)
            rig.close()

            failReads.set(true)
            assertThrows(IllegalStateException::class.java) {
                rig.open(work.resolve("s3-disk-broken"), listOf(StandInHead(1)), config, env = env)
            }

            failReads.set(false)
            rig.open(work.resolve("s3-disk-b"), listOf(StandInHead(1)), config, env = env)
            assertEquals(200, send("GET", "/server/api/keys", "x-api-key" to opsKey).statusCode())
        } finally {
            s3.stop(0)
        }
    }

    private fun startMockS3(objects: ConcurrentHashMap<String, ByteArray>, failReads: AtomicBoolean): HttpServer {
        val s3 = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s3.createContext("/") { ex -> handleS3(ex, objects, failReads.get()) }
        s3.start()
        return s3
    }

    private fun handleS3(ex: HttpExchange, objects: ConcurrentHashMap<String, ByteArray>, failReads: Boolean) {
        val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
        val key = ex.requestURI.path
        if (!auth.startsWith("AWS4-HMAC-SHA256 ")) {
            ex.sendResponseHeaders(403, -1)
        } else if (ex.requestMethod == "PUT") {
            objects[key] = ex.requestBody.readAllBytes()
            ex.sendResponseHeaders(200, -1)
        } else if (ex.requestMethod == "GET") {
            respondS3Get(ex, objects[key], failReads)
        } else {
            ex.sendResponseHeaders(405, -1)
        }
        ex.close()
    }

    private fun respondS3Get(ex: HttpExchange, data: ByteArray?, failReads: Boolean) {
        when {
            failReads -> ex.sendResponseHeaders(500, -1)
            data == null -> ex.sendResponseHeaders(404, -1)
            else -> {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.use { it.write(data) }
            }
        }
    }

    private fun send(method: String, path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${rig.port}$path"))
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, HttpRequest.BodyPublishers.noBody())
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
