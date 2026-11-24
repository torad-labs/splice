// A mode on a team server's listener (docs/specs/server-mode.md, "Mode routes"), as the daemon decides it: which modes
// a key holder reaches under `/server/mod/<name>/...`, which only an admin does, what a mode that throws answers, and
// that turning a mode on or off from the console or `PUT /api/modes` keeps those switches in splice.toml.
package splice.app.modes

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.mode.ModeSetting
import splice.core.server.ModAnswer
import splice.core.server.ModCaller
import splice.core.server.ModRequest
import splice.core.server.ModResponse
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.modes.api.FoundMode
import splice.modes.api.Mode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCatalog
import splice.modes.api.ModeKind
import splice.modes.api.ModeManifest
import splice.modes.api.ModeOffer
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class ModeServerRoutesTest {
    @TempDir
    lateinit var state: Path

    /** Answers `GET tide` with who asked, has no other path, and throws on `sink`. */
    private class HarborMode : Mode {
        override fun answer(call: ModeCall): ModeAnswer = ModeAnswer.Text("unused")

        override fun serve(request: ModRequest): ModResponse? = when (request.path) {
            listOf("tide") -> ModResponse(200, "text/plain", "high for ${request.caller.person}".toByteArray())
            listOf("sink") -> error("a secret the caller must not read")
            else -> null
        }
    }

    /** A mode that never says what it serves. */
    private class QuietMode : Mode {
        override fun answer(call: ModeCall): ModeAnswer = ModeAnswer.Text("unused")
    }

    private fun mode(name: String, handler: Mode) = FoundMode(
        ModeManifest(
            name,
            "A harbor.",
            name,
            handler.javaClass.name,
            emptyList(),
            ModeKind.DOOR,
            ModeOffer(on = true, only = emptyList(), off = emptyList(), callersOff = emptyList()),
        ),
        emptyList(),
        emptyList(),
        null,
        handler,
    )

    /** `harbor` serves key holders, `vault` (the same code) only admins, `dock` is carried with no server switch, and
     *  `quiet` serves but has nothing to serve. */
    private fun held(): DaemonModes {
        val found = listOf(
            mode("dock", HarborMode()),
            mode("harbor", HarborMode()),
            mode("quiet", QuietMode()),
            mode("vault", HarborMode()),
        )
        val settings = mapOf(
            "harbor" to ModeSetting(serverRoutes = true),
            "quiet" to ModeSetting(serverRoutes = true),
            "vault" to ModeSetting(serverRoutes = true, serverAdminOnly = true),
        )
        return DaemonModes(ModeCatalog(found, emptyList(), found.map { it.name }), settings, listOf("acme"), state)
    }

    private fun request(path: String, admin: Boolean = false): ModRequest {
        val caller = ModCaller("ada@team.example", "core", admin)
        return ModRequest("GET", listOf(path), caller, emptyMap(), emptyMap(), ByteArray(0))
    }

    private fun text(answer: ModAnswer): String =
        checkNotNull(assertInstanceOf(ModAnswer.Answered::class.java, answer).response).text()

    @Test
    fun `a mode whose table sets server_routes answers a key holder, and the rest are no mode at all`() {
        val routes = ModeServerRoutes(held())
        assertEquals("high for ada@team.example", text(routes.answer("harbor", request("tide"))))
        assertEquals(ModAnswer.NoSuchMod, routes.answer("dock", request("tide")))
        assertEquals(ModAnswer.NoSuchMod, routes.answer("nowhere", request("tide")))
        assertNull(assertInstanceOf(ModAnswer.Answered::class.java, routes.answer("quiet", request("tide"))).response)
        assertNull(assertInstanceOf(ModAnswer.Answered::class.java, routes.answer("harbor", request("x"))).response)
    }

    @Test
    fun `an admins-only mode refuses a member and answers an admin`() {
        val routes = ModeServerRoutes(held())
        assertEquals(ModAnswer.AdminsOnly, routes.answer("vault", request("tide")))
        assertEquals("high for ada@team.example", text(routes.answer("vault", request("tide", admin = true))))
    }

    @Test
    fun `a mode that throws is answered for, naming the exception and nothing it held`() {
        val answer = ModeServerRoutes(held()).answer("harbor", request("sink"))
        val failed = assertInstanceOf(ModAnswer.Failed::class.java, answer)
        assertEquals("mode harbor failed: IllegalStateException", failed.said)
    }

    @Test
    fun `a switch written from the console follows at once, and keeps the server switches in splice toml`() {
        val held = held()
        val routes = ModeServerRoutes(held)
        val file = state.resolve("splice.toml")
        Files.writeString(file, BASE + "\n[modes.vault]\nserver_routes = true\nserver_admin_only = true\n")
        val writer = TopologyWriter(file, state.resolve("backups"), TopologyParse(TopologyLoader::parse))

        val switched = ModeSwitch(held).put("vault", buildJsonObject { put("on", false) }, writer)
        assertInstanceOf(Switched.Done::class.java, switched)
        val written = TopologyLoader.parse(Files.readString(file)).modes.getValue("vault")
        assertEquals(ModeSetting(on = false, serverRoutes = true, serverAdminOnly = true), written)
        assertEquals(ModAnswer.AdminsOnly, routes.answer("vault", request("tide")))

        ModeSwitch(held).put("vault", buildJsonObject { put("on", null as Boolean?) }, writer)
        val cleared = TopologyLoader.parse(Files.readString(file)).modes.getValue("vault")
        assertEquals(ModeSetting(serverRoutes = true, serverAdminOnly = true), cleared)
        assertTrue("server_admin_only = true" in Files.readString(file))
    }
}

private const val BASE = """
[providers.local]
dialect = "openai-chat"
base_url = "http://127.0.0.1:3105/v1"
auth = { kind = "api-key", env = "LOCAL_API_KEY" }
[[providers.local.models]]
id = "m"
label = "M"
context_window = 200000
[heads.acme]
provider = "local"
port = 3102
discovery_prefix = "claude-acme--"
pinned_model = "m"
"""
