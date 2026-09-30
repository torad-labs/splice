package splice.topology

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

    fun checkDocument(text: String): Int {
        // Inline field names, API notation and placeholder values are not complete TOML documents.
        // Copyable topology examples must use toml fences to participate in this check.
        val blocks = fences.findAll(text).toList()
        blocks.forEach { block ->
            val body = block.groupValues[1]
            // These two fragments edit existing tables. Supply their required parent fields as
            // synthetic test context, never as copyable fake providers or heads in the document.
            val parent = when {
                "[heads.example.overrides]" in body && "[heads.example]" !in body -> """
                    [heads.example]
                    provider = "example"
                    port = 3105
                    discovery_prefix = "claude-example--"
                    pinned_model = "example-model"
                """.trimIndent()
                "[providers.codex.quirks]" in body && "[providers.codex]" !in body -> """
                    [providers.codex]
                    dialect = "openai-responses"
                    base_url = "https://example.invalid/v1"
                    auth = { kind = "chatgpt-oauth" }
                """.trimIndent()
                else -> ""
            }
            checkKnobs("$parent\n$body")
        }
        return blocks.size
    }

    fun checkExample(text: String): Int {
        checkKnobs(text)
        // Standalone commented settings lack a table; prose is not a copyable optional block.
        val blocks = optional.findAll(text).toList()
        blocks.forEach { block ->
            checkKnobs(block.value.lineSequence().joinToString("\n") { it.removePrefix("#").trimStart() })
        }
        return blocks.size
    }

    fun checkKnobs(document: String) {
        val topology = TopologyLoader.parse(document)
        val layers = listOf(topology.defaults) + topology.heads.values.map { it.overrides }
        layers.forEach { layer ->
            require(layer.keys.all { it in knobsByKey }) { "unknown topology knob: ${layer.keys - knobsByKey.keys}" }
        }
    }
}
