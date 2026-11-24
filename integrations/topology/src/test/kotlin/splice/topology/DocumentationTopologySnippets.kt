package splice.topology

import splice.core.capability.TomlTree
import splice.core.config.knobsByKey

/** Fences and whole optional example blocks are parsed by the daemon's own loader. */
internal object DocumentationTopologySnippets {
    private val fences = Regex(
        "(?m)^[ \\t]{0,3}```toml[ \\t]*\\n(.*?)^[ \\t]{0,3}```[ \\t]*$",
        RegexOption.DOT_MATCHES_ALL,
    )
    private const val COMMENTED_TABLE = "^#[ \\t]+\\[{1,2}[^\\]\\n]+\\]{1,2}[ \\t]*(?:#[^\\n]*)?\\n"
    private val optional = Regex(
        "(?m)$COMMENTED_TABLE(?:$COMMENTED_TABLE|^#[ \\t]+[A-Za-z_][A-Za-z0-9_.-]*[ \\t]*=[^\\n]*\\n)*",
    )
    private val headSubTable = Regex("(?m)^\\[heads\\.([A-Za-z0-9_-]+)\\.")

    fun checkDocument(text: String): Int {
        // Inline field names, API notation and placeholder values are not complete TOML documents.
        // Copyable topology examples must use toml fences to participate in this check.
        val blocks = fences.findAll(text).toList()
        blocks.forEach { block ->
            val body = block.groupValues[1]
            try {
                // A hooks fragment belongs to a mode.toml manifest, not splice.toml.
                if (body.trimStart().startsWith("[hooks]")) {
                    requireNotNull(TomlTree.read(body)) { "invalid mode manifest TOML" }
                } else {
                    checkKnobs(withDocumentContext(body))
                }
            } catch (e: Exception) {
                val line = text.take(block.range.first).count { it == '\n' } + 1
                throw IllegalArgumentException("TOML fence at line $line: ${e.message}", e)
            }
        }
        return blocks.size
    }

    // These fragments edit existing tables. Supply required fields here rather than in copyable examples.
    private fun withDocumentContext(body: String): String = when {
        "[heads.example.overrides]" in body && "[heads.example]" !in body -> """
            [heads.example]
            provider = "example"
            port = 3105
            discovery_prefix = "claude-example--"
            pinned_model = "example-model"
        """.trimIndent() + "\n$body"
        "[providers.codex.quirks]" in body && "[providers.codex]" !in body ->
            "[providers.codex]\n$codexFields\n$body"
        body.trimStart().startsWith("[providers.codex]") && "dialect =" !in body ->
            body.replaceFirst("[providers.codex]", "[providers.codex]\n$codexFields")
        else -> body
    }

    private val codexFields = """
        dialect = "openai-responses"
        base_url = "https://example.invalid/v1"
        auth = { kind = "chatgpt-oauth" }
    """.trimIndent()

    fun checkExample(text: String): Int {
        checkKnobs(text)
        // Standalone commented settings lack a table; prose is not a copyable optional block.
        val blocks = optional.findAll(text).toList()
        blocks.forEach { block ->
            val body = block.value.lineSequence().joinToString("\n") { it.removePrefix("#").trimStart() }
            checkKnobs("${headOf(text, body)}\n$body")
        }
        return blocks.size
    }

    /** The example's own `[heads.NAME]` table when [body] is a commented sub-table of a head it does not declare,
     *  such as a head's rates card: the card is copied under that head, so it is parsed with it. */
    private fun headOf(text: String, body: String): String {
        val head = headSubTable.find(body)?.groupValues?.get(1) ?: return ""
        if ("[heads.$head]" in body) return ""
        val table = Regex("(?ms)^\\[heads\\.${Regex.escape(head)}\\][^\\n]*\\n(.*?)(?=^\\[)").find(text)
        return table?.let { "[heads.$head]\n${it.groupValues[1]}" } ?: ""
    }

    fun checkKnobs(document: String) {
        val topology = TopologyLoader.parse(document)
        val layers = listOf(topology.defaults) + topology.heads.values.map { it.overrides }
        layers.forEach { layer ->
            require(layer.keys.all { it in knobsByKey }) { "unknown topology knob: ${layer.keys - knobsByKey.keys}" }
        }
    }
}
