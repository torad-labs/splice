// PORT-OF: codex-rs/code-mode-protocol/src/description.rs @ rust-v0.164.0.
package splice.upstream.codemode.description

import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeText
import splice.upstream.codemode.CodeModeToolSearch
import java.util.concurrent.atomic.AtomicReference

/** A single immutable snapshot cache includes every description-affecting setting and catalog field. */
internal object CodeModeDescriptions {
    private data class Key(
        val enabled: List<CodeModeManual.NestedTool>,
        val deferred: Boolean,
        val options: CodeModeManual.DescriptionOptions,
    )

    private data class Cached(val key: Key, val text: String)

    private val cache = AtomicReference<Cached?>()
    private val template = resource("exec-description.txt")
    private val visibleImage = resource("image-helper-visible.txt")
    private val hiddenImage = resource("image-helper-hidden.txt")
    private val mcp = resource("mcp-typescript.txt")
    val wait: String = resource("wait-description.txt")

    fun description(
        enabled: List<CodeModeManual.NestedTool>,
        deferred: Boolean,
        options: CodeModeManual.DescriptionOptions,
    ): String {
        val key = Key(enabled.toList(), deferred, options)
        cache.get()?.takeIf { it.key == key }?.let { return it.text }
        return build(enabled, options).also { cache.set(Cached(key, it)) }
    }

    private fun build(tools: List<CodeModeManual.NestedTool>, options: CodeModeManual.DescriptionOptions): String {
        val sections = base(options)
        if (!options.codeModeOnly) return sections.joinToString("\n\n")
        val preamble = options.messages.mcpPreamble ?: mcp
        if (preamble.isNotEmpty()) sections += "Shared MCP Types:\n```ts\n$preamble\n```"
        if (tools.isNotEmpty()) sections += nested(tools).joinToString("\n\n")
        return sections.joinToString("\n\n")
    }

    private fun base(options: CodeModeManual.DescriptionOptions): MutableList<String> {
        val sections = ArrayList<String>()
        val image = if (options.imageDetailVisible) visibleImage else hiddenImage
        val description = (options.messages.execDescription ?: template)
            .replace("{{ default_exec_yield_time_ms }}", options.defaultYieldTimeMs.toString())
            .replace("{{ image_helper }}", image)
        if (description.isNotEmpty()) sections += description
        val ranked = options.discovery == CodeModeManual.Discovery.RANKED_SEARCH && !options.codeModeOnly
        val defaultGuidance = if (ranked) "$DEFERRED_GUIDANCE\n\n${CodeModeToolSearch.GUIDANCE}" else DEFERRED_GUIDANCE
        val guidance = options.messages.deferredGuidance ?: defaultGuidance
        if (guidance.isNotEmpty()) sections += guidance
        return sections
    }

    private fun nested(tools: List<CodeModeManual.NestedTool>): List<String> {
        val sections = ArrayList<String>()
        val descriptions = namespaceDescriptions(tools)
        var namespace: String? = null
        val ordered = tools.sortedWith(
            compareBy<CodeModeManual.NestedTool, String?>(nullsFirst(CodeModeText.order)) { it.metadata.namespace }
                .thenBy(CodeModeText.order) { it.name }.thenBy(CodeModeText.order) { it.codeModeName },
        ).distinctBy { it.codeModeName }
        for (tool in ordered) {
            val metadata = tool.metadata
            if (namespace != metadata.namespace) {
                val description = descriptions[metadata.namespace].orEmpty()
                if (description.isNotEmpty()) sections += "## ${metadata.namespace}\n$description"
                namespace = metadata.namespace
            }
            val global = CodeModeManual.identifier(tool.codeModeName)
            val heading = if (global == tool.codeModeName) "### `$global`" else "### `$global` (`${tool.codeModeName}`)"
            val sample = CodeModeText.trim(CodeModeSamples.render(tool))
            sections += if (sample.isEmpty()) heading else "$heading\n$sample"
        }
        return sections
    }

    private fun namespaceDescriptions(tools: List<CodeModeManual.NestedTool>): Map<String, String> {
        val descriptions = LinkedHashMap<String, String>()
        for (tool in tools) {
            val namespace = tool.metadata.namespace ?: continue
            val description = CodeModeText.trim(tool.metadata.namespaceDescription.orEmpty())
            if (descriptions[namespace].isNullOrEmpty()) descriptions[namespace] = description
        }
        return descriptions
    }

    private fun resource(name: String): String = checkNotNull(
        javaClass.getResourceAsStream("/splice/upstream/codemode/$name"),
    ) {
        "missing bundled Codex code-mode description $name"
    }.bufferedReader(Charsets.UTF_8).use { it.readText().trimEnd() }
}

private const val DEFERRED_GUIDANCE =
    "Some deferred nested tools may be omitted from this description. They are still available on the " +
        "global `tools` object and listed in `ALL_TOOLS`.\n" +
        "To find one, filter `ALL_TOOLS` by `name` and `description`.\n\nTool availability can change between calls."
