// A server's keys as its operator issues, lists and revokes them (docs/specs/server-mode.md, F1 and decision 4), asked
// the way the CLI, the control plane and an admin's key all ask: through the one set of operations they share.
package splice.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

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
            setOf("prefix", "person", "name", "source", "created_at", "expires_at", "last_used_at", "revoked_at"),
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
}
