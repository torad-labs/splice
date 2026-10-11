package splice.core.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CredentialKeyTest {
    @Test
    fun `wire-equivalent credentials share a key independently of unrelated headers`() {
        val first = CredentialKey.fromHeaders(
            mapOf("Authorization" to "Bearer synthetic-a", "x-api-key" to "synthetic-k"),
        )
        assertEquals(
            first,
            CredentialKey.fromHeaders(
                linkedMapOf(
                    "X-Api-Key" to "synthetic-k",
                    "AUTHORIZATION" to "obsolete",
                    "authorization" to "Bearer synthetic-a",
                    "anthropic-beta" to "synthetic",
                ),
            ),
        )
        assertNotEquals(
            first,
            CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-b", "x-api-key" to "synthetic-k")),
        )
        assertNotEquals(first, CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-a")))
    }

    @Test
    fun `a declared custom API key carrier has the same isolation as standard carriers`() {
        val first = CredentialKey.fromHeaders(mapOf("X-Synthetic-Key" to "synthetic-a"), "x-synthetic-key")
        assertNotEquals(null, first)
        assertEquals(first, CredentialKey.fromHeaders(mapOf("x-synthetic-key" to "synthetic-a"), "X-Synthetic-Key"))
        assertNotEquals(first, CredentialKey.fromHeaders(mapOf("X-Synthetic-Key" to "synthetic-b"), "x-synthetic-key"))
    }

    @Test
    fun `a missing credential is not a proved shared login`() {
        assertNull(CredentialKey.fromHeaders(mapOf("Authorization" to "", "x-api-key" to " ")))
        assertNull(CredentialKey.fromHeaders(mapOf("anthropic-version" to "synthetic")))
        assertNotEquals(
            CredentialKey.fromHeaders(mapOf("Authorization" to "synthetic")),
            CredentialKey.fromHeaders(mapOf("x-api-key" to "synthetic")),
        )
    }
}
