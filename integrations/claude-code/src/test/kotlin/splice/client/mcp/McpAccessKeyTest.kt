package splice.client.mcp

// NEW: v0.4.0 FEATURES.md §8 — MCP-only authority and deliberate revocation on management-key rotation.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpAccessKeyTest {
    @Test
    fun `scoped capability is stable and never accepts the management key`() {
        val key = McpAccessKey { "synthetic-management-secret" }
        val scoped = key()
        assertNotEquals("synthetic-management-secret", scoped)
        assertEquals(scoped, key())
        assertTrue(key.matchesBearer("Bearer $scoped"))
        assertFalse(key.matchesBearer("Bearer synthetic-management-secret"))
        assertFalse(key.matchesBearer(null))
        assertFalse(key.matchesBearer("Bearer ${scoped.dropLast(1)}x"))
    }

    // The derivation is pinned to a vector computed outside the JVM (Python hmac, 2026-09-23): the
    // key rides in generated MCP client configs, so a change in scope, encoding or hex case would
    // strand every configured client while every relative assertion above stayed green.
    @Test
    fun `the scoped capability is HMAC-SHA256 of the MCP scope under the management key`() {
        assertEquals(
            "185a66cb46afccec43484e34f38a7f1db9727aee3c1105ae17208d7adc36ca0c",
            McpAccessKey { "synthetic-management-secret" }(),
        )
    }

    @Test
    fun `management rotation explicitly revokes the old scoped capability`() {
        var management = "synthetic-old"
        val key = McpAccessKey { management }
        val old = key()
        management = "synthetic-new"
        assertFalse(key.matchesBearer("Bearer $old"))
        assertTrue(key.matchesBearer("Bearer ${key()}"))
    }
}
