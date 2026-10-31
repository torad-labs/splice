package splice.upstream.codemode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.upstream.codemode.search.CodeModeMcpSearchIdentity
import splice.upstream.codemode.search.CodeModeMcpSearchNames

class CodeModeMcpSearchNamesTest {
    @Test
    fun `regular raw names retain source flat identity without a declaration delimiter`() {
        assertEquals(
            CodeModeMcpSearchIdentity("mcp__parityecho", "echo", "echo", "parity"),
            CodeModeMcpSearchNames.regularUnchanged("parity", "echo", mapOf("parity" to listOf("echo"))),
        )
        assertEquals(
            CodeModeMcpSearchIdentity("mcp__parityecho", "echo", "echo", "mcp__parity"),
            CodeModeMcpSearchNames.regularUnchanged("mcp__parity", "echo", mapOf("mcp__parity" to listOf("echo"))),
        )
        assertEquals(
            CodeModeMcpSearchIdentity("mcp__Parity42echo_7", "echo_7", "echo_7", "Parity42"),
            CodeModeMcpSearchNames.regularUnchanged("Parity42", "echo_7", mapOf("Parity42" to listOf("echo_7"))),
        )
    }

    @Test
    fun `source sanitizer changes remain unmatched rather than guessed as raw identity`() {
        for (name in listOf("", "hyphen-name", "dot.name", "space name", "λ", "\uD83D\uDE00")) {
            assertNull(CodeModeMcpSearchNames.regularUnchanged(name, "echo", mapOf(name to listOf("echo"))))
            assertNull(CodeModeMcpSearchNames.regularUnchanged("parity", name, mapOf("parity" to listOf(name))))
        }
    }

    @Test
    fun `same-namespace sanitized aliases and duplicate raw catalog members remain unmatched`() {
        assertNull(
            CodeModeMcpSearchNames.regularUnchanged(
                "parity",
                "echo_tool",
                mapOf("parity" to listOf("echo_tool", "echo-tool")),
            ),
        )
        assertNull(CodeModeMcpSearchNames.regularUnchanged("parity", "echo", mapOf("parity" to listOf("echo", "echo"))))
        assertNull(CodeModeMcpSearchNames.regularUnchanged("parity", "missing", mapOf("parity" to listOf("echo"))))
    }

    @Test
    fun `raw server aliases cannot share a proved source namespace`() {
        assertNull(
            CodeModeMcpSearchNames.regularUnchanged(
                "parity",
                "echo",
                mapOf("parity" to listOf("echo"), "mcp__parity" to listOf("other")),
            ),
        )
        assertNull(
            CodeModeMcpSearchNames.regularUnchanged(
                "a_b",
                "echo",
                mapOf("a_b" to listOf("echo"), "a-b" to listOf("other")),
            ),
        )
        assertEquals(
            CodeModeMcpSearchIdentity("mcp__parityecho", "echo", "echo", "parity"),
            CodeModeMcpSearchNames.regularUnchanged(
                "parity",
                "echo",
                mapOf("parity" to listOf("echo"), "mcp__parity" to emptyList()),
            ),
        )
    }

    @Test
    fun `the source concatenation is ambiguous even when server and member tuples differ`() {
        val catalogs = mapOf("a" to listOf("bc"), "ab" to listOf("c"))
        assertNull(CodeModeMcpSearchNames.regularUnchanged("a", "bc", catalogs))
        assertNull(CodeModeMcpSearchNames.regularUnchanged("ab", "c", catalogs))
    }

    @Test
    fun `unknown catalogs block possible identity collisions without hiding unrelated known tools`() {
        assertNull(CodeModeMcpSearchNames.regularUnchanged("parity", "echo", mapOf("parity" to null)))
        assertNull(
            CodeModeMcpSearchNames.regularUnchanged("a", "bc", mapOf("a" to listOf("bc"), "ab" to null)),
        )
        assertNull(
            CodeModeMcpSearchNames.regularUnchanged("a", "bc", mapOf("a" to listOf("bc"), "mcp__a" to null)),
        )
        assertEquals(
            CodeModeMcpSearchIdentity("mcp__abcecho", "echo", "echo", "abc"),
            CodeModeMcpSearchNames.regularUnchanged("abc", "echo", mapOf("abc" to listOf("echo"), "xyz" to null)),
        )
    }

    @Test
    fun `the unchanged source name budget includes prefix and the reserved two bytes`() {
        val tool = "x".repeat(120)
        assertEquals(
            CodeModeMcpSearchIdentity("mcp__p$tool", tool, tool, "p"),
            CodeModeMcpSearchNames.regularUnchanged("p", tool, mapOf("p" to listOf(tool))),
        )
        val longer = tool + "x"
        assertNull(CodeModeMcpSearchNames.regularUnchanged("p", longer, mapOf("p" to listOf(longer))))
        val server = "s".repeat(120)
        assertEquals(
            CodeModeMcpSearchIdentity("mcp__${server}x", "x", "x", server),
            CodeModeMcpSearchNames.regularUnchanged(server, "x", mapOf(server to listOf("x"))),
        )
        val longerServer = server + "s"
        assertNull(CodeModeMcpSearchNames.regularUnchanged(longerServer, "x", mapOf(longerServer to listOf("x"))))
    }

    @Test
    fun `names that could be a source hash rename and reserved connector namespaces remain unmatched`() {
        val hashed = "echo_0123456789ab"
        assertNull(CodeModeMcpSearchNames.regularUnchanged("parity", hashed, mapOf("parity" to listOf(hashed))))
        val server = "parity_0123456789ab"
        assertNull(CodeModeMcpSearchNames.regularUnchanged(server, "echo", mapOf(server to listOf("echo"))))
        for (reserved in listOf("codex_apps", "codex_apps__gmail", "mcp__codex_apps", "mcp__codex_apps__gmail")) {
            assertNull(CodeModeMcpSearchNames.regularUnchanged(reserved, "echo", mapOf(reserved to listOf("echo"))))
        }
    }
}
