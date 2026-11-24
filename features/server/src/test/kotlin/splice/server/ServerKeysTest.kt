// A server's keys as its operator issues, lists and revokes them (docs/specs/server-mode.md, F1 and decision 4), asked
// the way the CLI, the control plane and an admin's key all ask: through the one set of operations they share.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.server.keys.KeyImport
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.HexFormat
import kotlin.coroutines.CoroutineContext

internal class ServerKeysTest {
    @TempDir lateinit var folder: Path

    private val logged = mutableListOf<String>()

    private fun server(): ServerMode {
        val config = ServerConfig(listen = "127.0.0.1:0", commands = listOf("claude-team"))
        return ServerMode(config, folder, Dispatchers.IO, LogSink { logged += it })
    }

    private fun created(server: ServerMode, person: String, name: String? = null, expires: String? = null): JsonObject {
        val asked = buildJsonObject {
            put("person", person)
            name?.let { put("name", it) }
            expires?.let { put("expires", it) }
        }
        return assertInstanceOf(ServerAnswer.Done::class.java, runBlocking { server.keyApi.create(asked) }).body
    }

    private fun listed(server: ServerMode, person: String? = null): List<JsonObject> =
        assertInstanceOf(ServerAnswer.Done::class.java, server.keyApi.list(person)).body
            .getValue("keys").jsonArray.map { it.jsonObject }

    private fun text(row: JsonObject, field: String): String = row.getValue(field).jsonPrimitive.content

    @Test
    fun `a key is issued to a lowercased email in the documented shape, with no expiry unless one is asked`() {
        val server = server()
        val minted = created(server, "Ada@Team.Example", name = "laptop")
        val key = text(minted, "key")
        assertTrue(Regex("^spk_[a-z0-9]{8}_[A-Za-z0-9]{32,}$").matches(key), "not the documented shape")
        assertEquals(key.split('_').take(2).joinToString("_"), text(minted, "prefix"))
        assertEquals("ada@team.example", text(minted, "person"))
        assertEquals("laptop", text(minted, "name"))
        assertEquals("operator", text(minted, "source"))
        assertEquals(JsonNull, minted.getValue("expires_at"))
        val expiring = created(server, "bo@team.example", expires = "30d")
        assertTrue(expiring.getValue("expires_at") !is JsonNull, "a lifetime asked for set no expiry")
    }

    @Test
    fun `no file and no log line holds a key, and a list shows its prefix and never its secret`() {
        val server = server()
        val minted = created(server, "ada@team.example")
        val secret = text(minted, "key").substringAfterLast('_')
        val kept = Files.list(folder).use { files -> files.toList().joinToString("\n") { Files.readString(it) } }
        assertTrue(kept.contains(text(minted, "prefix")), "the keys file does not hold the key's record")
        assertFalse(kept.contains(secret), "a file in the server's folder holds the key")
        assertFalse(logged.any { secret in it }, "the log holds the key")

        val row = listed(server).single()
        assertEquals(text(minted, "prefix"), text(row, "prefix"))
        assertEquals(
            setOf(
                "prefix", "person", "name", "source", "device", "created_at", "expires_at", "last_used_at",
                "revoked_at", "superseded_by",
            ),
            row.keys,
        )
        assertFalse(row.toString().contains(secret), "a list shows the key")
        assertEquals(JsonNull, row.getValue("last_used_at"), "a key nobody used has a last use")
    }

    @Test
    fun `a person that is no email, or a lifetime that is no duration, is refused and issues nothing`() {
        val server = server()
        val noEmail = runBlocking { server.keyApi.create(buildJsonObject { put("person", "not-an-email") }) }
        assertInstanceOf(ServerAnswer.Refused::class.java, noEmail)
        val asked = buildJsonObject {
            put("person", "ada@team.example")
            put("expires", "soon")
        }
        val noDuration = assertInstanceOf(ServerAnswer.Refused::class.java, runBlocking { server.keyApi.create(asked) })
        assertTrue(noDuration.why.contains("expires"), noDuration.why)
        assertTrue(listed(server).isEmpty(), "a refused request issued a key")
    }

    @Test
    fun `a list can be one person's, and offboarding revokes each of that person's keys and nobody else's`() {
        val server = server()
        repeat(2) { created(server, "olga@team.example", name = "device $it") }
        val kept = created(server, "ada@team.example")
        assertEquals(2, listed(server, "OLGA@team.example").size)

        val answer = runBlocking { server.keyApi.revokePerson("OLGA@team.example") }
        val revoked = assertInstanceOf(ServerAnswer.Done::class.java, answer).body
        assertEquals("2", text(revoked, "revoked"))
        assertEquals("olga@team.example", text(revoked, "person"))
        assertTrue(listed(server, "olga@team.example").none { it.getValue("revoked_at") is JsonNull })
        val other = listed(server, "ada@team.example").single()
        assertEquals(text(kept, "prefix"), text(other, "prefix"))
        assertEquals(JsonNull, other.getValue("revoked_at"), "offboarding one person revoked another's key")
    }

    @Test
    fun `revoking a prefix no key has says so`() {
        val refused = runBlocking { server().keyApi.revoke("spk_nosuchkey") }
        val why = assertInstanceOf(ServerAnswer.Refused::class.java, refused)
        assertEquals(404, why.status.value)
    }

    @Test
    fun `a keys file that cannot be read stops server mode, and is left as it was`() {
        val file = folder.resolve("keys.json")
        Files.writeString(file, "{ this is not the keys file")
        assertThrows(Exception::class.java) { server() }
        assertEquals("{ this is not the keys file", Files.readString(file))
    }

    @Test
    fun `heldIn verifies candidate runs by hash across lengths and ignores unregistered suffixes`() {
        val keys = ServerKeys(folder.resolve("keys.json"), Dispatchers.IO)
        val minted = runBlocking { keys.mint(KeyRequest("ada@team.example")) }
        val mintedKey = minted.reveal()
        val key64 = "team_longer64_" + "b".repeat(64)
        val key32 = "spk_short032_" + "d".repeat(32)
        val mismatchedActual = "legacy_actual99_" + "c".repeat(48)
        runBlocking {
            assertNotNull(keys.importKey(KeyImport("ada@team.example", "team_longer64", sha256(key64), null, null)))
            assertNotNull(keys.importKey(KeyImport("ada@team.example", "spk_short032", sha256(key32), null, null)))
            assertNotNull(
                keys.importKey(KeyImport("bo@team.example", "spk_stored01", sha256(mismatchedActual), null, null)),
            )
        }
        assertFalse(keys.heldIn("${minted.row.prefix}_${"z".repeat(40)}"))
        assertFalse(keys.heldIn("ref=${minted.row.prefix}_${"0".repeat(32)}; ok"))
        assertFalse(keys.heldIn("spk_stored01_${"c".repeat(48)}"))
        assertTrue(keys.heldIn(mintedKey))
        assertTrue(keys.heldIn("${mintedKey}extra"))
        assertTrue(keys.heldIn("x-note=$key64; ok"))
        assertTrue(keys.heldIn("${key64}extraSuffix"))
        assertTrue(keys.heldIn("spk_aaaaaaaa_${"x".repeat(37)}${key64}tail99"))
        assertTrue(keys.heldIn("Bearer $key32"))
        assertTrue(keys.heldIn("${key32}extraSuffix"))
        assertTrue(keys.heldIn("${mismatchedActual}extraSuffix"))
        assertInstanceOf(KeyVerdict.Admitted::class.java, runBlocking { keys.admit(mismatchedActual) })
        assertTrue(keys.heldIn("header=$mismatchedActual;"))
        runBlocking { keys.revoke(minted.row.prefix) }
        assertTrue(keys.heldIn(mintedKey))
    }

    @Test
    fun `an older concurrent admission finishing after a newer one keeps the newer lastUsedAt in memory and on disk`() {
        val file = folder.resolve("keys.json")
        var nowMs = 5L
        val clock = object : Clock() {
            override fun instant(): Instant = Instant.ofEpochMilli(nowMs)

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this
        }
        var defer = false
        val pendingIo = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (defer) pendingIo.addLast(block) else block.run()
            }
        }
        val keys = ServerKeys(file, io, clock)
        runBlocking { keys.mint(KeyRequest("ada@team.example", source = SIGNIN_SOURCE, device = "laptop")) }
        nowMs = 6L
        val newer = runBlocking { keys.mint(KeyRequest("ada@team.example", source = SIGNIN_SOURCE, device = "laptop")) }

        nowMs = 10L
        defer = true
        runBlocking {
            val olderAdmission = launch { keys.admit(newer.reveal()) }
            yield()
            assertEquals(1, pendingIo.size)
            pendingIo.removeFirst().run()
            nowMs = 20L
            defer = false
            assertInstanceOf(KeyVerdict.Admitted::class.java, keys.admit(newer.reveal()))
            assertEquals(20L, keys.list().single { it.prefix == newer.row.prefix }.lastUsedAt)
            olderAdmission.join()
        }
        assertEquals(20L, keys.list().single { it.prefix == newer.row.prefix }.lastUsedAt)
        runBlocking { keys.keepUse(olderThanMs = 0) }
        assertEquals(20L, ServerKeys(file, io, clock).list().single { it.prefix == newer.row.prefix }.lastUsedAt)
    }

    @Test
    fun `cancelling a mutation after disk replacement still updates memory and failed writes change nothing`() {
        val file = folder.resolve("keys.json")
        var defer = false
        val pendingIo = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (defer) pendingIo.addLast(block) else block.run()
            }
        }
        val keys = ServerKeys(file, io)
        val minted = runBlocking { keys.mint(KeyRequest("ada@team.example")) }
        defer = true
        runBlocking {
            val revokeJob = launch { keys.revoke(minted.row.prefix) }
            yield()
            assertEquals(1, pendingIo.size)
            pendingIo.removeFirst().run()
            revokeJob.cancel()
            revokeJob.join()
            assertTrue(revokeJob.isCancelled)
        }
        assertNotNull(ServerKeys(file, io).list().single().revokedAt)
        assertNotNull(keys.list().single().revokedAt)
        assertInstanceOf(KeyVerdict.Revoked::class.java, runBlocking { keys.admit(minted.reveal()) })

        defer = false
        val snapshot = keys.list()
        Files.delete(file)
        Files.createDirectories(file.resolve("blocking-entry"))
        assertThrows(IOException::class.java) { runBlocking { keys.mint(KeyRequest("bo@team.example")) } }
        assertEquals(snapshot, keys.list())
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
