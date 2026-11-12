@file:OptIn(ExperimentalForeignApi::class)

package splice.hub.front

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.posix.mkdtemp
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class McpResultCaptureTest {
    private val server = "server"
    private val callbackId = "toolu_splice_00000000-0000-0000-0000-000000000000"

    // Independently computed SHA-256, matching the daemon's java.security.MessageDigest filename.
    private val filename = "c6282249b6e7f4094515726112ae244d2318aa4ebe39084d94e0fa2f68c790a5.json"
    private val arguments = checkNotNull(JsonText.objectOrNull("""{"value":0,"flag":false,"empty":[]}"""))
    private val raw = checkNotNull(
        JsonText.objectOrNull(
            """{
                "content":[{"type":"text","text":"visible"}],
                "structuredContent":{"value":0,"flag":false,"empty":[],"nested":{"_meta":"public"}},
                "isError":false,"_meta":{"private":"retained"}
            }""",
        ),
    )

    @Test
    fun callbackHashMatchesTheSharedSha256Filename() {
        assertEquals(filename, McpResultHash.filename(callbackId))
    }

    @Test
    fun actualNativeCaptureRetainsTheWholeResultAndDurableConflicts() {
        val fixture = NativeReceiptFixture(filename)
        try {
            val identity = assertNotNull(McpResultIdentities.fromCall(server, request(callbackId)))
            val restored = assertNotNull(McpResultIdentities.restore(identity.state()))
            val answer = JsonObject(mapOf(Rpc.RESULT to raw))
            NativeMcpResults(fixture.files, Clock()).capture(restored, answer)
            val first = fixture.read()
            assertEquals(raw, (first["outcome"] as? JsonObject)?.get("value"))
            assertEquals(arguments, first["arguments"])
            assertEquals(JsonPrimitive(callbackId), first["callbackId"])
            NativeMcpResults(fixture.files, Clock()).capture(restored, answer)
            assertEquals(first, fixture.read(), "identical capture keeps its original receipt")

            val conflicting = restored.copy(tool = "different")
            NativeMcpResults(fixture.files, Clock()).capture(conflicting, answer)
            assertEquals(JsonPrimitive(true), fixture.read()["conflict"])
            NativeMcpResults(fixture.files, Clock()).capture(restored, answer)
            assertEquals(
                JsonPrimitive(true),
                fixture.read()["conflict"],
                "a reconstructed writer cannot restore the first value",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun onlyActualOwnedCallMetadataIsCaptured() {
        assertNull(McpResultIdentities.fromCall(server, request("foreign-callback")))
        val ordinaryText =
            checkNotNull(
                JsonText.objectOrNull("""{"method":"tools/call","params":{"name":"structured","arguments":{}}}"""),
            )
        assertNull(McpResultIdentities.fromCall(server, ordinaryText))
        assertEquals(arguments, assertNotNull(McpResultIdentities.fromCall(server, request(callbackId))).arguments)
    }

    @Test
    fun aTransportErrorCannotReuseAnEarlierNativeReceipt() {
        val fixture = NativeReceiptFixture(filename)
        try {
            val identity = assertNotNull(McpResultIdentities.fromCall(server, request(callbackId)))
            NativeMcpResults(fixture.files, Clock()).capture(identity, JsonObject(mapOf(Rpc.RESULT to raw)))
            NativeMcpResults(
                fixture.files,
                Clock(),
            ).capture(identity, JsonObject(mapOf("error" to JsonObject(emptyMap()))))
            assertEquals(JsonPrimitive(true), fixture.read()["conflict"])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun actualRpcFailureRetainsItsTypedPacketWithoutInventingARawResult() {
        val fixture = NativeReceiptFixture(filename)
        try {
            val identity = assertNotNull(McpResultIdentities.fromCall(server, request(callbackId)))
            val error = checkNotNull(
                JsonText.objectOrNull(
                    """{"code":-32001,"message":"Fixed fixture RPC failure","data":{"fixture":true}}""",
                ),
            )
            val answer = JsonObject(mapOf("error" to error))
            NativeMcpResults(fixture.files, Clock()).capture(identity, answer)
            val outcome = assertNotNull(fixture.read()["outcome"] as? JsonObject)
            assertEquals(JsonPrimitive("RpcError"), outcome["type"])
            assertEquals(error, outcome["error"])
            assertNull(outcome["value"], "only the source-backed provider normalizes an RPC error")
        } finally {
            fixture.close()
        }
    }

    private fun request(id: String): JsonObject = JsonObject(
        mapOf(
            Rpc.METHOD to JsonPrimitive("tools/call"),
            Rpc.PARAMS to JsonObject(
                mapOf(
                    "name" to JsonPrimitive("structured"),
                    "arguments" to arguments,
                    "_meta" to JsonObject(mapOf("claudecode/toolUseId" to JsonPrimitive(id))),
                ),
            ),
        ),
    )
}

private class NativeReceiptFixture(private val filename: String) {
    private val temporary = memScoped {
        val pattern = "/tmp/splice-mcp-receipt-XXXXXX".cstr.getPointer(this)
        checkNotNull(mkdtemp(pattern)).toKString()
    }
    val files = HubFiles("$temporary/mcp-hub")
    private val directory = McpResultDirectory(files.toolResultsDir)

    fun read(): JsonObject = directory.locked {
        checkNotNull(directory.read(filename)?.let(JsonText::objectOrNull))
    }

    fun close() {
        unlink("${files.toolResultsDir}/$filename")
        unlink("${files.toolResultsDir}/.lock")
        rmdir(files.toolResultsDir)
        rmdir(temporary)
    }
}
