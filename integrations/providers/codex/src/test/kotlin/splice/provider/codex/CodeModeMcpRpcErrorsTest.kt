package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.JsonScalars
import splice.provider.codex.callbacks.mcp.CodeModeMcpRpcErrors

class CodeModeMcpRpcErrorsTest {
    @Test
    fun `owned RPC failures resolve to the twelve actual signed Codex CLI values`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/codemode/mcp-rpc-errors-0.165.json"))
            .use { it.readBytes().decodeToString() }
        val proof = Json.parseToJsonElement(bytes) as JsonObject
        assertEquals("360319d8dee62d87e2d59fc0c21805d79519cfed", JsonScalars.str(proof, "sourceRevision"))
        val vectors = proof.getValue("vectors") as JsonArray
        assertEquals(12, vectors.size)
        for (element in vectors) {
            val vector = element as JsonObject
            val result = CodeModeMcpRpcErrors.result(
                checkNotNull(JsonScalars.str(vector, "server")),
                checkNotNull(JsonScalars.str(vector, "tool")),
                vector.getValue("error") as JsonObject,
            )
            assertEquals(vector.getValue("result"), result, JsonScalars.str(vector, "id"))
            assertEquals(JsonPrimitive(true), result?.get("isError"))
            assertTrue(
                result?.keys == setOf("content", "isError"),
                "a failed RPC supplies no raw metadata or structured content",
            )
        }
    }

    @Test
    fun `an invalid ErrorData cannot be promoted into a successful captured result`() {
        for (error in listOf(
            "{}", """{"code":-32001}""", """{"code":"-32001","message":"error"}""",
            """{"code":-2147483649,"message":"error"}""", """{"code":2147483648,"message":"error"}""",
            """{"code":1.5,"message":"error"}""", """{"code":1e0,"message":"error"}""",
            """{"code":1,"message":null}""", """{"code":1,"message":false}""",
        )) {
            assertNull(
                CodeModeMcpRpcErrors.result("parity", "echo", Json.parseToJsonElement(error) as JsonObject),
                error,
            )
        }
    }
}
