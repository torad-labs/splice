package splice.app.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.client.mcp.names.ClaudeMcpToolNames
import splice.core.util.JsonScalars
import splice.provider.codex.catalog.CodeModeMcpSearchCatalog
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.search.CodeModeMcpSearchDescriptions
import splice.upstream.codemode.search.CodeModeMcpSearchInfo
import splice.upstream.codemode.search.CodeModeMcpSearchNames

/** Current configuration-bound observations; an unknown server is retained with a null catalog. */
internal fun interface CodeModeSharedMcpCatalogs {
    fun read(): Map<String, JsonObject?>
}

/** Binds raw shared catalogs forward to this request's exact client declarations; flattened names are never parsed. */
internal class CodeModeSharedMcpSearch(
    private val catalogs: CodeModeSharedMcpCatalogs,
) : CodeModeMcpSearchCatalog {
    private data class Raw(val server: String, val tool: JsonObject) {
        val name: String = JsonScalars.strIfString(tool["name"])
        val frontendName: String = ClaudeMcpToolNames.name(server, name)
    }

    override fun entries(tools: List<CodeModeManual.NestedTool>): Map<String, CodeModeMcpSearchInfo> {
        val observed = catalogs.read().mapValues { (_, catalog) ->
            val members = catalog?.get("tools") as? JsonArray
            members?.filterIsInstance<JsonObject>()?.takeIf { it.size == members.size }
        }
        val raw = observed.flatMap { (server, members) -> members.orEmpty().map { Raw(server, it) } }
            .groupBy { it.frontendName }
        val advertised = tools.filter { it.name.startsWith("mcp__") }.groupBy { it.name }
        val bound = advertised.mapNotNull { (name, declarations) ->
            val declaration = declarations.singleOrNull() ?: return@mapNotNull null
            raw[name]?.singleOrNull()?.takeIf { matches(it, declaration) }?.let { declaration to it }
        }
        if (bound.size != advertised.size) return emptyMap()
        if (bound.map { it.first.codeModeName }.distinct().size != bound.size) return emptyMap()
        val names = observed.mapValues { (_, members) -> members?.map { JsonScalars.strIfString(it["name"]) } }
        return bound.mapNotNull { (declaration, raw) ->
            val identity = CodeModeMcpSearchNames.regularUnchanged(raw.server, raw.name, names)
                ?: return@mapNotNull null
            val schema = raw.tool["inputSchema"] as JsonObject
            declaration.codeModeName to CodeModeMcpSearchInfo(
                identity = identity,
                descriptions = CodeModeMcpSearchDescriptions(
                    title = JsonScalars.str(raw.tool, "title"),
                    description = JsonScalars.str(raw.tool, "description"),
                ),
                inputPropertyNames = (schema["properties"] as? JsonObject)?.keys?.toList().orEmpty(),
                declarationName = declaration.codeModeName,
            )
        }.toMap()
    }

    private fun matches(raw: Raw, declaration: CodeModeManual.NestedTool): Boolean =
        raw.tool["inputSchema"] is JsonObject &&
            raw.tool["inputSchema"] == declaration.inputSchema &&
            JsonScalars.str(raw.tool, "description").orEmpty() == declaration.description
}
