package splice.app.mods.install

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ConsolePorts
import splice.app.control.ControlAuth
import splice.app.mods.ModsMount
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.server.KeyRequest
import splice.server.ServerKeys
import java.nio.file.Files
import java.nio.file.Path

class ModInstallRouteTest {
    @TempDir
    lateinit var root: Path

    private val manifest = """
        {"name":"board","version":"1.2.3","description":"Track sessions.",
         "surfaces":["console"],"capabilities":["watch-sessions"],
         "pages":[{"name":"board","title":"Board"}]}
    """.trimIndent()

    @Test
    fun `a copy keeps its own files and changes need explicit acceptance`() = testApplication {
        val rig = rig()
        application { routing { rig.mount.register(this) } }
        val source = folder()
        val installed = client.post("/api/mods") {
            bearerAuth(rig.key)
            setBody(install(source))
        }
        assertEquals(HttpStatusCode.Created, installed.status)
        assertTrue(installed.bodyAsText().contains("watch-sessions"))
        Files.writeString(source.resolve(".splice-mod/mod.json"), "{}")

        val listed = client.get("/api/mods") { bearerAuth(rig.key) }
        val row = Json.parseToJsonElement(listed.bodyAsText()).jsonObject.getValue("mods").jsonArray.single().jsonObject
        assertEquals("board", row.getValue("name").jsonPrimitive.content)
        assertEquals("1.2.3", row.getValue("version").jsonPrimitive.content)
        assertEquals("console", row.getValue("surfaces").jsonArray.single().jsonPrimitive.content)
        assertEquals("watch-sessions", row.getValue("capabilities").jsonArray.single().jsonPrimitive.content)
        assertEquals("true", row.getValue("enabled").jsonPrimitive.content)
        assertEquals("0", row.getValue("dropped").jsonPrimitive.content)
        assertEquals("failed", row.getValue("state").jsonPrimitive.content)
        assertTrue(listed.bodyAsText().contains("Bun not found"))

        val copy = root.resolve("state/mods/installed/board")
        Files.writeString(copy.resolve("added.ts"), "// added")
        val refused = client.put("/api/mods/board") {
            bearerAuth(rig.key)
            setBody("""{"enabled":true}""")
        }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        val refusal = refused.bodyAsText()
        assertTrue(refusal.contains("added.ts (added)"), refusal)
        assertTrue(refusal.contains("splice mod enable board --accept"), refusal)
        val accepted = client.put("/api/mods/board") {
            bearerAuth(rig.key)
            setBody("""{"enabled":true,"accept":true}""")
        }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals(
            HttpStatusCode.OK,
            client.put("/api/mods/board") {
                bearerAuth(rig.key)
                setBody("""{"enabled":true}""")
            }.status,
        )
    }

    @Test
    fun `a linked mod skips file checks and remains on disk after removal`() = testApplication {
        val rig = rig()
        application { routing { rig.mount.register(this) } }
        val source = folder()
        val linked = client.post("/api/mods") {
            bearerAuth(rig.key)
            setBody(install(source, linked = true))
        }
        assertEquals(HttpStatusCode.Created, linked.status)
        assertTrue(linked.bodyAsText().contains("not hash-checked"))
        Files.writeString(source.resolve("added.ts"), "// added")
        assertEquals(
            HttpStatusCode.OK,
            client.put("/api/mods/board") {
                bearerAuth(rig.key)
                setBody("""{"enabled":true}""")
            }.status,
        )
        assertTrue(
            client.put("/api/mods/board") {
                bearerAuth(rig.key)
                setBody("""{"enabled":false}""")
            }.bodyAsText().contains("disabled"),
        )
        assertEquals(HttpStatusCode.OK, client.delete("/api/mods/board") { bearerAuth(rig.key) }.status)
        assertTrue(Files.exists(source.resolve("added.ts")))
        assertEquals("""{"mods":[]}""", client.get("/api/mods") { bearerAuth(rig.key) }.bodyAsText())
    }

    @Test
    fun `only the management key installs a tarball and a folder without a manifest is named`() = testApplication {
        val rig = rig()
        application { routing { rig.mount.register(this) } }
        val tar = root.resolve("board.tar")
        TarArchiveOutputStream(Files.newOutputStream(tar)).use { archive ->
            val bytes = manifest.toByteArray()
            val entry = TarArchiveEntry("board/.splice-mod/mod.json").apply { size = bytes.size.toLong() }
            archive.putArchiveEntry(entry)
            archive.write(bytes)
            archive.closeArchiveEntry()
        }
        val team = ServerKeys(root.resolve("team/keys.json"), Dispatchers.IO).mint(KeyRequest("team@example.test"))
        val denied = client.post("/api/mods") {
            bearerAuth(team.reveal())
            setBody(install(tar))
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        val notAMod = client.post("/api/mods") {
            bearerAuth(rig.key)
            setBody(install(Files.createDirectory(root.resolve("empty"))))
        }
        assertEquals(HttpStatusCode.BadRequest, notAMod.status)
        assertTrue(notAMod.bodyAsText().contains(".splice-mod/mod.json"))
        assertEquals(
            HttpStatusCode.Created,
            client.post("/api/mods") {
                bearerAuth(rig.key)
                setBody(install(tar))
            }.status,
        )
        assertFalse(client.get("/api/mods") { bearerAuth(rig.key) }.bodyAsText().contains("linkedPath"))
    }

    @Test
    fun `an incomplete package names its missing fields and declared files`() = testApplication {
        val rig = rig()
        application { routing { rig.mount.register(this) } }
        val source = folder()
        Files.writeString(
            source.resolve(".splice-mod/mod.json"),
            """
                {"name":"Bad-Name","tools":["status"],
                 "views":[{"uri":"ui://board/card","name":"Card","file":"card.html"}]}
            """.trimIndent(),
        )
        Files.writeString(
            Files.createDirectory(source.resolve("hooks")).resolve("hooks.json"),
            """{"modules":["./missing.ts"]}""",
        )
        val installed = client.post("/api/mods") {
            bearerAuth(rig.key)
            setBody(install(source))
        }
        assertEquals(HttpStatusCode.BadRequest, installed.status)
        val message = installed.bodyAsText()
        for (
            problem in listOf(
                "Bad-Name",
                "version",
                "description",
                "tools/status.json",
                "tools/status.md",
                "card.html",
                "hooks/missing.ts",
            )
            ) assertTrue(message.contains(problem), message)
    }

    private fun rig(): Rig {
        val paths = StatePaths(baseOverride = root.resolve("state"), envReader = { name ->
            root.toString().takeIf { name == "HOME" }
        })
        val key = MgmtKey(paths, LogSink {})
        val ports = ConsolePorts().apply { mods = InstalledMods(paths) }
        return Rig(key.get(), ModsMount(ports, ControlAuth(key, LogSink {}).guard()))
    }

    private fun folder(): Path {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(Files.createDirectory(source.resolve(".splice-mod")).resolve("mod.json"), manifest)
        return source
    }

    private fun install(source: Path, linked: Boolean = false): String = buildJsonObject {
        put("source", source.toString())
        if (linked) put("link", true)
    }.toString()

    private data class Rig(val key: String, val mount: ModsMount)
}
