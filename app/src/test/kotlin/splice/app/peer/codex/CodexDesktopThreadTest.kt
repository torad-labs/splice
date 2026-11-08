package splice.app.peer.codex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.sessions.peer.codex.CodexBinding
import splice.sessions.peer.codex.TurnStart
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE

class CodexDesktopThreadTest {
    @Test
    fun `desktop owner carries plain input with inherited settings and disconnect closes the thread`(
        @TempDir directory: Path,
    ) = runBlocking {
        val conversationId = "conversation-desktop-1"
        val clientId = "message-client-1"
        val text = "Continue the existing desktop conversation"
        val fixture = CodexDesktopStandIn(directory.resolve("ipc/ipc.sock"), conversationId)
        val channel = fixture.connect()
        val thread = CodexDesktopThread(CodexIpcRpc(CodexIpcSocket(channel), Dispatchers.IO), conversationId)
        val reading = async(Dispatchers.IO) { thread.read() }

        try {
            assertIdleAndActive(thread, fixture, conversationId)
            assertMinimalTurnRequest(thread, fixture, conversationId, clientId, text)
            fixture.disconnectOwner()
            assertThreadClosed(thread, conversationId)
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { reading.await() }.contains("closed"))
        } finally {
            thread.close()
            fixture.close()
            reading.cancelAndJoin()
        }
    }

    private suspend fun assertIdleAndActive(
        thread: CodexDesktopThread,
        fixture: CodexDesktopStandIn,
        conversationId: String,
    ) {
        assertEquals(CodexGreeting.Loaded("idle"), withTimeout(TEST_TIMEOUT_MS) { thread.greet() })
        assertInitializationAndDiscovery(fixture, conversationId)
        assertStatus(thread, "idle")
        fixture.publishActiveStatus()
        assertStatus(thread, "active")
    }

    private fun assertInitializationAndDiscovery(fixture: CodexDesktopStandIn, conversationId: String) {
        val initialization = requireNotNull(fixture.nextRequest("initialize"))
        val initializeParams = requireNotNull(initialization["params"] as? JsonObject)
        assertEquals("splice", JsonScalars.str(initializeParams, "clientType"))

        val discovery = requireNotNull(fixture.nextRequest("thread-owner-discovery"))
        assertEquals(1, JsonScalars.int(discovery, "version"))
        val discoveryParams = requireNotNull(discovery["params"] as? JsonObject)
        assertEquals(setOf("hostId", "conversationId"), discoveryParams.keys)
        assertEquals("local", JsonScalars.str(discoveryParams, "hostId"))
        assertEquals(conversationId, JsonScalars.str(discoveryParams, "conversationId"))
    }

    private suspend fun assertStatus(thread: CodexDesktopThread, expected: String) {
        val event = withTimeout(TEST_TIMEOUT_MS) { thread.notifications.receive() }
        assertEquals("thread/status/changed", event.method)
        val status = requireNotNull(event.params?.get("status") as? JsonObject)
        assertEquals(expected, JsonScalars.str(status, "type"))
    }

    private suspend fun assertMinimalTurnRequest(
        thread: CodexDesktopThread,
        fixture: CodexDesktopStandIn,
        conversationId: String,
        clientId: String,
        text: String,
    ) {
        assertEquals(
            TurnStart.Started("desktop-turn-1"),
            withTimeout(TEST_TIMEOUT_MS) { thread.startTurn(clientId, text) },
        )
        val start = requireNotNull(fixture.nextRequest("thread-follower-start-turn"))
        assertEquals("desktop-owner-1", JsonScalars.str(start, "targetClientId"))
        assertEquals("splice-desktop-client", JsonScalars.str(start, "sourceClientId"))
        val params = requireNotNull(start["params"] as? JsonObject)
        assertEquals(setOf("conversationId", "turnStart"), params.keys)
        assertEquals(conversationId, JsonScalars.str(params, "conversationId"))
        assertTurnPayload(requireNotNull(params["turnStart"] as? JsonObject), conversationId, clientId, text)
    }

    private fun assertTurnPayload(turnStart: JsonObject, conversationId: String, clientId: String, text: String) {
        assertEquals(setOf("request", "context"), turnStart.keys)
        val request = requireNotNull(turnStart["request"] as? JsonObject)
        assertEquals(setOf("threadId", "clientUserMessageId", "input"), request.keys)
        assertEquals(conversationId, JsonScalars.str(request, "threadId"))
        assertEquals(clientId, JsonScalars.str(request, "clientUserMessageId"))
        val input = requireNotNull(request["input"] as? JsonArray)
        assertEquals(1, input.size)
        assertTextInput(requireNotNull(input.singleOrNull()?.jsonObject), text)
        val context = requireNotNull(turnStart["context"] as? JsonObject)
        assertEquals(setOf("inheritThreadSettings"), context.keys)
        assertEquals("true", context["inheritThreadSettings"].toString())
    }

    private fun assertTextInput(input: JsonObject, text: String) {
        assertEquals(setOf("type", "text", "text_elements"), input.keys)
        assertEquals("text", JsonScalars.str(input, "type"))
        assertEquals(text, JsonScalars.str(input, "text"))
        assertEquals(0, (input["text_elements"] as? JsonArray)?.size)
    }

    private suspend fun assertThreadClosed(thread: CodexDesktopThread, conversationId: String) {
        val event = withTimeout(TEST_TIMEOUT_MS) {
            for (candidate in thread.notifications) {
                if (candidate.method == "thread/closed") return@withTimeout candidate
            }
            error("the desktop reader closed without a thread/closed event")
        }
        assertEquals("thread/closed", event.method)
        assertEquals(conversationId, JsonScalars.str(event.params, "threadId"))
    }

    @Test
    fun `a desktop handover without acknowledgement remains uncertain`(@TempDir directory: Path) = runBlocking {
        val conversationId = "conversation-desktop-no-ack"
        val fixture = CodexDesktopStandIn(
            directory.resolve("ipc/ipc.sock"),
            conversationId,
            dropStartTurnReply = true,
        )
        val channel = fixture.connect()
        val thread = CodexDesktopThread(CodexIpcRpc(CodexIpcSocket(channel), Dispatchers.IO), conversationId)
        val reading = async(Dispatchers.IO) { thread.read() }

        try {
            assertEquals(CodexGreeting.Loaded("idle"), withTimeout(TEST_TIMEOUT_MS) { thread.greet() })
            val result = withTimeout(TEST_TIMEOUT_MS) { thread.startTurn("message-uncertain", "Do not duplicate me") }
            assertTrue(result is TurnStart.Unknown)
            assertTrue(fixture.nextRequest("thread-follower-start-turn") != null)
        } finally {
            thread.close()
            fixture.close()
            reading.cancelAndJoin()
        }
    }

    @Test
    fun `desktop owner is selected while an unowned ipc thread falls through to app server discovery`(
        @TempDir directory: Path,
    ) = runBlocking {
        val home = directory.resolve("codex-home")
        val conversationId = "conversation-owned-desktop"
        val socketPath = home.resolve("ipc/ipc.sock")
        val fixture = CodexDesktopStandIn(socketPath, conversationId)
        Files.setPosixFilePermissions(socketPath.parent, setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE))
        Files.setPosixFilePermissions(socketPath, setOf(OWNER_READ, OWNER_WRITE))

        val opened = try {
            CodexAppServers(Dispatchers.IO).open(CodexBinding(conversationId, home.toString(), null))
        } finally {
            fixture.close()
        }
        assertTrue(opened is CodexOpen.Open)
        (opened as CodexOpen.Open).thread.close()

        val unownedHome = directory.resolve("unowned-home")
        val unownedId = "conversation-not-loaded"
        val unownedSocket = unownedHome.resolve("ipc/ipc.sock")
        val unownedFixture = CodexDesktopStandIn(unownedSocket, unownedId, ownerFound = false)
        Files.setPosixFilePermissions(unownedSocket.parent, setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE))
        Files.setPosixFilePermissions(unownedSocket, setOf(OWNER_READ, OWNER_WRITE))
        val fallback = try {
            CodexAppServers(Dispatchers.IO).open(CodexBinding(unownedId, unownedHome.toString(), null))
        } finally {
            unownedFixture.close()
        }
        assertTrue(fallback is CodexOpen.Gone)
    }
}

private const val TEST_TIMEOUT_MS = 20_000L
