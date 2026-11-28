package splice.app.desktop.setup

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DesktopHooksFilesTest {
    @Test
    fun `the overlay registers startup peers Teams login and tool activity with only the tool and session ids`(
        @TempDir home: Path,
    ) = UserHome.within(home) {
        val received = LinkedBlockingQueue<List<String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { call ->
            val capture = listOf(
                call.requestURI.path,
                call.requestHeaders.getFirst("Authorization").orEmpty(),
                call.requestHeaders.getFirst("X-Splice-Foreground-Owner").orEmpty(),
                call.requestBody.reader().readText(),
            )
            received.add(capture)
            call.sendResponseHeaders(200, -1)
            call.close()
        }
        server.start()
        try {
            val f = DesktopFileRig(home)
            f.putIn(server.address.port)
            val hooks = hooks(f.state)
            assertEquals(setOf("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse"), hooks.keys)
            val startup = hooks.getValue("SessionStart").jsonArray
            assertTrue(startup.any { it.jsonObject["matcher"]?.jsonPrimitive?.content == "startup" })
            assertTrue(startup.toString().contains("peer attach --hook"))
            assertTrue(hooks.getValue("UserPromptSubmit").toString().contains("splice-login-hook.sh"))
            Files.writeString(f.state.resolve("turn-auth-header"), "Authorization: Bearer hook-fixture\n")
            for ((event, phase) in mapOf("PreToolUse" to "start", "PostToolUse" to "end")) {
                val group = hooks.getValue(event).jsonArray.single().jsonObject
                assertEquals("*", group.getValue("matcher").jsonPrimitive.content)
                run(group, event, home)
                val call = requireNotNull(received.poll())
                assertEquals(listOf("/hooks/foreground/door", "Bearer hook-fixture", OWNER), call.take(3))
                val expected = """{"session_id":"session","tool_use_id":"tool","phase":"$phase"}"""
                assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(call.last()))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a stalled or missing daemon cannot block a tool`(@TempDir home: Path) = UserHome.within(home) {
        val f = DesktopFileRig(home)
        val command = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { stalled ->
            f.putIn(stalled.localPort)
            val group = hooks(f.state).getValue("PreToolUse").jsonArray.single().jsonObject
            run(group, "PreToolUse", home)
            group
        }
        run(command, "PreToolUse", home)
    }

    private fun hooks(state: Path): JsonObject =
        Json.parseToJsonElement(Files.readString(state.resolve("desktop-hooks.json"))).jsonObject
            .getValue("hooks").jsonObject

    private fun run(group: JsonObject, event: String, home: Path) {
        val cmd = group.getValue("hooks").jsonArray.single().jsonObject.getValue("command").jsonPrimitive.content
        val process = ProcessBuilder("/bin/sh", "-c", cmd).directory(home.toFile()).redirectErrorStream(true).apply {
            environment().clear()
            environment()["HOME"] = home.toString()
            environment()["PATH"] = "/usr/bin:/bin"
            environment()["SPLICE_FOREGROUND_OWNER"] = OWNER
        }.start()
        val input = """{"tool_input":{"session_id":"nested","password":"private"},"session_id":"session",""" +
            """"tool_use_id":"tool","hook_event_name":"$event"}"""
        process.outputStream.bufferedWriter().use { it.write(input) }
        try {
            assertTrue(process.waitFor(4, TimeUnit.SECONDS), "tool held by an unavailable daemon")
            assertEquals(0, process.exitValue())
            assertEquals("", process.inputStream.bufferedReader().readText())
        } finally {
            process.destroyForcibly()
        }
    }
}

private const val OWNER = "12345678-abcd-1234-abcd-123456789012"
