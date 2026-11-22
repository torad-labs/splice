// NEW: a mode the catalog found whole (modes/README.md): its manifest, its tools and resources as its folder writes
// them, its prompt, and its handler.
package splice.modes.api

import kotlinx.serialization.json.JsonObject

/** Whether splice answers a mode's calls itself or serves it as an MCP server. */
public enum class ModeKind(public val wire: String) {
    /** splice holds and answers each call, and the client never sees it. */
    DOOR("door"),

    /** A real MCP server the daemon answers itself, under the mode's `server` name. */
    MCP("mcp"),
}

/** What `mode.toml` says, checked. */
public data class ModeManifest(
    val name: String,
    val summary: String,
    val server: String,
    val handler: String,
    val tools: List<String>,
    val kind: ModeKind,
    val offer: ModeOffer,
)

/** Who the manifest offers the mode to: [on] for every session by default, the heads it is [only] for (empty: every
 *  head), the heads it is never [off]ered on, and the callers it is never offered to. */
public data class ModeOffer(
    val on: Boolean,
    val only: List<String>,
    val off: List<String>,
    val callersOff: List<ModeCaller>,
)

/** One tool: its [name] as the manifest writes it, its description (`tools/<tool>.md`, trimmed), its input schema
 *  (`tools/<tool>.json` as written) and its `_meta` (`tools/<tool>.meta.json`), null when that file is absent. */
public data class ModeTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val meta: JsonObject?,
    val offeredAs: String,
)

/** One `[[resources]]` entry: a `ui://` [uri], its name and mimeType, its `_meta` as written, and the content of its
 *  `file` under `resources/` (null when the handler gives it). */
public data class ModeResource(
    val uri: String,
    val name: String,
    val mimeType: String,
    val meta: JsonObject?,
    val content: String?,
)

/** A whole mode: its manifest, tools in manifest order, resources, its prompt (`prompt.md`, trimmed) or null, and
 *  the handler the ServiceLoader made. */
public data class FoundMode(
    val manifest: ModeManifest,
    val tools: List<ModeTool>,
    val resources: List<ModeResource>,
    val prompt: String?,
    val handler: Mode,
) {
    val name: String get() = manifest.name
}
