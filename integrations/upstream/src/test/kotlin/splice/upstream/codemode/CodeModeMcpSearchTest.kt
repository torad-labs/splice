package splice.upstream.codemode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import splice.upstream.codemode.search.CodeModeMcpSearchDescriptions
import splice.upstream.codemode.search.CodeModeMcpSearchIdentity
import splice.upstream.codemode.search.CodeModeMcpSearchInfo
import splice.upstream.codemode.search.CodeModeMcpSearchText
import splice.upstream.codemode.search.CodeModeSearchCorpus

class CodeModeMcpSearchTest {
    @Test
    fun `MCP search uses proved identities and raw catalog documentation in source order`() {
        val info = CodeModeMcpSearchInfo(
            CodeModeMcpSearchIdentity("mcp__server__callable", "callable", "raw-name", "server"),
            CodeModeMcpSearchDescriptions(
                " title ",
                "\u2003description\n",
                " connector ",
                " namespace ",
                listOf("plugin", " "),
            ),
            listOf("z", "a", "\uD83D\uDE00", "\uE000"),
        )
        assertEquals(
            "mcp__server__callable callable raw-name server title description " +
                "connector namespace plugin a z \uE000 \uD83D\uDE00",
            CodeModeMcpSearchText.build(info),
        )
    }

    @Test
    fun `agent plugin namespace limit is a UTF8 prefix before Rust trimming`() {
        val info = CodeModeMcpSearchInfo(
            CodeModeMcpSearchIdentity("flat", "callable", "raw", "server"),
            CodeModeMcpSearchDescriptions(namespaceDescription = " ".repeat(999) + "λ after"),
            agentPlugin = true,
        )
        assertEquals("flat callable raw server", CodeModeMcpSearchText.build(info))
        assertEquals("flat callable raw server λ after", CodeModeMcpSearchText.build(info.copy(agentPlugin = false)))
    }

    @Test
    fun `MCP entries keep per-tool search sources while ordinary namespace grouping stays intact`() {
        val first = CodeModeManual.NestedTool(
            "first",
            "generic",
            null,
            metadata = CodeModeManual.Metadata(namespace = "ns"),
        )
        val second = first.copy(name = "second")
        val info = CodeModeMcpSearchInfo(
            CodeModeMcpSearchIdentity("nsfirst", "first", "original", "server"),
            declarationName = first.codeModeName,
        )
        val corpus = CodeModeSearchCorpus.entries(listOf(first, second), mapOf(first.codeModeName to info))
        assertEquals(listOf(listOf(first), listOf(second)), corpus.map { it.tools })
        assertEquals("nsfirst first original server", corpus.first().searchText)
        assertEquals(listOf(first, second), CodeModeSearchCorpus.entries(listOf(first, second)).single().tools)
        assertThrows(IllegalArgumentException::class.java) {
            CodeModeSearchCorpus.entries(
                listOf(first),
                mapOf(first.codeModeName to info.copy(declarationName = "other")),
            )
        }
    }
}
