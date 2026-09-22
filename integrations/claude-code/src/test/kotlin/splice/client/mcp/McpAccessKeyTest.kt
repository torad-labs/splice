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
