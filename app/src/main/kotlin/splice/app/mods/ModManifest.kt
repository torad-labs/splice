// NEW: a mod's `.splice-mod/mod.json` as Splice reads it (the mods v2 contract's ModManifest, PageDeclaration,
// CommandDeclaration, ViewDeclaration and ToolDefinition), once ModFolderCheck found the folder whole.
package splice.app.mods

import kotlinx.serialization.json.JsonObject

/** What a mod declares: who it is, what it says it does, and the tools, pages, commands and views it brings. */
internal data class ModManifest(
    val name: String,
    val version: String,
    val description: String,
    val author: String?,
    val surfaces: List<String>,
    val capabilities: List<String>,
    val declared: ModDeclared,
)

/** The things a mod brings, each read from the manifest and, for a tool, its two files. */
internal data class ModDeclared(
    val tools: List<DeclaredTool>,
    val pages: List<DeclaredPage>,
    val commands: List<DeclaredCommand>,
    val views: List<DeclaredView>,
)

/** A tool as its three files write it: `tools/<name>.json`, `tools/<name>.md` and `tools/<name>.meta.json`. */
internal data class DeclaredTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val meta: JsonObject?,
)

internal data class DeclaredPage(val name: String, val title: String)

internal data class DeclaredCommand(val name: String, val description: String)

internal data class DeclaredView(val uri: String, val name: String, val mimeType: String?, val file: String?)
