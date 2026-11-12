package splice.app.provider.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.mcp.McpToolResultStore
import splice.core.util.WallClock
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** The callback is ordinary Anthropic, and the actual MCP transport is the sole source of its structured value. */
@Timeout(120)
class CodeModeCapturedMcpTest {
    @Test
    fun `ordinary callbacks deliver captured raw MCP data to the native guest and preserve it after restart`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val clock = AtomicLong(1_000_000)
        val limits = McpToolResultStore.Limits(maxAgeMs = 1_000)
        val store = McpToolResultStore(paths, WallClock { clock.get() }, limits)
        val transport = CapturedMcpTransport(paths, store)
        val chat = CapturedMcpConversation("captured-session")
        val arguments = arguments("actual", failed = true)
        lateinit var raw: JsonObject
        try {
            transport.start()
            rig(tmp, store) { rig ->
                rig.upstream.source(callSource("captured", arguments))
                val use = chat.begin(rig)
                val id = use.getValue("id").jsonPrimitive.content
                assertEquals(arguments, use.getValue("input").jsonObject)
                raw = transport.call(id, arguments)
                val stored =
                    assertInstanceOf(McpToolResultStore.Read.Found::class.java, store.read(id, arguments))
                val outcome = assertInstanceOf(McpToolResultStore.Outcome.RawResult::class.java, stored.receipt.outcome)
                assertEquals(raw, outcome.value)
                chat.finish(rig, use, raw)
                assertGuestValue(raw, rig.upstream.observation("captured"))
                clock.addAndGet(limits.maxAgeMs)
                assertSame(McpToolResultStore.Read.Missing, store.read(id, arguments))
            }
            val restartedStore = McpToolResultStore(paths, WallClock { clock.get() }, limits)
            rig(tmp, restartedStore) { restarted ->
                restarted.upstream.source("text(JSON.stringify({tag:'new-guest',value:'running-after-restart'}));")
                chat.next(restarted)
                assertGuestValue(raw, restarted.upstream.observation("captured"))
                assertEquals(
                    JsonPrimitive("running-after-restart"),
                    restarted.upstream.observation("new-guest")["value"],
                )
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun `missing foreign or mismatched captures leave JSON looking ordinary content as a string`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val store = McpToolResultStore(paths)
        val transport = CapturedMcpTransport(paths, store)
        try {
            transport.start()
            rig(tmp, store) { rig ->
                CaptureMiss.entries.forEach { miss ->
                    val tag = "missing-${miss.name}"
                    val arguments = arguments(tag, failed = false)
                    val chat = CapturedMcpConversation(tag)
                    rig.upstream.source(callSource(tag, arguments))
                    val use = chat.begin(rig)
                    val id = use.getValue("id").jsonPrimitive.content
                    val raw = miss.call(transport, id, arguments)
                    chat.finish(rig, use, raw)
                    assertEquals(plainValue(), rig.upstream.observation(tag)["value"], miss.name)
                }
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun `another session cannot consume a captured callback even when it replays the same client history`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val store = McpToolResultStore(paths)
        val transport = CapturedMcpTransport(paths, store)
        try {
            transport.start()
            rig(tmp, store) { rig ->
                val arguments = arguments("session-owner", failed = true)
                val owner = CapturedMcpConversation("callback-owner")
                rig.upstream.source(callSource("owned-guest", arguments))
                val use = owner.begin(rig)
                val id = use.getValue("id").jsonPrimitive.content
                val raw = transport.call(id, arguments)
                rig.upstream.source("text(JSON.stringify({tag:'foreign-guest',value:'separate-session'}));")
                rig.send("another-session", owner.withResult(use, raw))
                assertEquals(JsonPrimitive("separate-session"), rig.upstream.observation("foreign-guest")["value"])
                val ordinary = "[is_error=true]\n${CapturedMcpPeer.TEXT}${CapturedMcpPeer.TAIL}"
                assertEquals(JsonPrimitive(ordinary), rig.upstream.ordinaryOutput(id))
                owner.finish(rig, use, raw)
                assertGuestValue(raw, rig.upstream.observation("owned-guest"))
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun `an actual peer RPC error resolves to the source CallToolResult envelope in the native guest`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val store = McpToolResultStore(paths)
        val transport = CapturedMcpTransport(paths, store)
        try {
            transport.start()
            rig(tmp, store) { rig ->
                val arguments =
                    buildJsonObject { put("value", "rpc-error"); put("failed", true); put("rpcError", true) }
                val chat = CapturedMcpConversation("rpc-error-session")
                rig.upstream.source(callSource("rpc-error", arguments))
                val use = chat.begin(rig)
                val id = use.getValue("id").jsonPrimitive.content
                val error = transport.rpcError(id, arguments)
                val stored =
                    assertInstanceOf(McpToolResultStore.Read.Found::class.java, store.read(id, arguments))
                val outcome = assertInstanceOf(McpToolResultStore.Outcome.RpcError::class.java, stored.receipt.outcome)
                assertEquals(error, outcome.error)
                chat.finish(rig, use, errorResult("The permission client reports the MCP invocation failed"))
                val sourceText = "tool call error: tool call failed for `capture/inspect`\n\nCaused by:\n" +
                    "    Mcp error: -32001: Fixed fixture RPC failure({\"fixture\":true})"
                assertEquals(errorResult(sourceText), rig.upstream.observation("rpc-error")["value"])
            }
        } finally {
            transport.close()
        }
    }

    private suspend fun rig(tmp: Path, store: McpToolResultStore, body: suspend (CapturedMcpRig) -> Unit) {
        val rig = CapturedMcpRig(tmp.resolve("head"), store)
        try {
            rig.start()
            body(rig)
        } finally {
            rig.close()
        }
    }

    private fun assertGuestValue(raw: JsonObject, observation: JsonObject) {
        val value = observation.getValue("value").jsonObject
        assertEquals(JsonObject(raw - "_meta"), value)
        assertEquals(JsonPrimitive(true), value["isError"])
        assertFalse("_meta" in value)
        val userData = value.getValue("structuredContent").jsonObject.getValue("userData").jsonObject
        assertEquals(JsonPrimitive(true), userData.getValue("_meta").jsonObject["visible"])
    }

    private fun arguments(value: String, failed: Boolean): JsonObject = buildJsonObject {
        put("value", value); put("failed", failed)
    }

    private fun errorResult(text: String): JsonObject = buildJsonObject {
        put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) })))
        put("isError", true)
    }

    private fun callSource(tag: String, arguments: JsonObject): String =
        "text(JSON.stringify({tag:${JsonPrimitive(tag)},value:await tools.mcp__capture__inspect($arguments)}));"

    private fun plainValue(): JsonPrimitive = JsonPrimitive("${CapturedMcpPeer.TEXT}\n${CapturedMcpPeer.TAIL}")

    private enum class CaptureMiss {
        MISSING, FOREIGN_ID, NAME, ARGUMENTS;

        suspend fun call(transport: CapturedMcpTransport, id: String, arguments: JsonObject): JsonObject = when (this) {
            MISSING -> transport.call(null, arguments)
            FOREIGN_ID -> transport.call("toolu_splice_${UUID.randomUUID()}", arguments)
            NAME -> transport.call(id, arguments, "other")
            ARGUMENTS -> transport.call(id, JsonObject(arguments + ("value" to JsonPrimitive("different"))))
        }
    }
}
