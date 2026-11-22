// NEW: modes-mcp (Nov 21, 2026) — the mcp modes as MCP servers the daemon answers itself (modes/README.md, `kind`).
// A door mode's tools ride the model's request and are answered inside the turn; an mcp mode is a server the client
// lists and calls as any other, so its tools reach the client's own tool surface and its `ui://` resources reach the
// client that can draw them. The MCP host asks this for a server by name and head (InProcessMcp), and gets one only
// when a mode of that `server` name is on for that head: an off mode's name is nobody's, as it is in the launch entry.
package splice.app.modes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.mcp.inprocess.InProcessMcp
import splice.control.mcp.inprocess.InProcessMcpServer
import splice.modes.api.FoundMode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeKind
import splice.modes.api.ModeRule
import splice.modes.api.ModeSession
import splice.modes.api.ModeStore

/** The modes the daemon holds now, or null before boot assigned them and in a daemon that carries none. */
internal fun interface ModesNow {
    fun held(): DaemonModes?
}

/** The mcp modes that are on, each as the server its manifest names. */
internal class ModeMcp(private val modes: ModesNow) : InProcessMcp {
    /** The mcp modes on for [head] and offered to [caller] (a manifest's word for one, `door` or `head`), in name
     *  order: the servers a launch there lists for its client. A caller no manifest names is offered none. */
    fun on(head: String, caller: String): List<FoundMode> {
        val held = modes.held() ?: return emptyList()
        val asking = ModeCaller.entries.firstOrNull { it.wire == caller } ?: return emptyList()
        return held.catalog.modes.filter { mode ->
            mode.manifest.kind == ModeKind.MCP && ModeRule.offeredTo(mode.manifest, asking) && held.state(mode, head).on
        }
    }

    override fun server(name: String, head: String?): InProcessMcpServer? {
        val held = modes.held() ?: return null
        val mode = held.catalog.modes.firstOrNull { it.manifest.kind == ModeKind.MCP && it.manifest.server == name }
            ?: return null
        val on = if (head == null) held.everyHead(mode).on else held.state(mode, head).on
        return if (on) ModeMcpServer(mode, held.store(mode.name), head) else null
    }
}

/** One mcp mode as its server: its tools and resources as its folder writes them, and its handler's answers. */
internal class ModeMcpServer(
    private val mode: FoundMode,
    private val store: ModeStore,
    private val head: String?,
) : InProcessMcpServer {
    override fun tools(): JsonArray = JsonArray(
        mode.tools.map { tool ->
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("inputSchema", tool.schema)
                tool.meta?.let { put("_meta", it) }
            }
        },
    )

    /** The handler's answer as a tool result. The session is the client's MCP session: the transport has no other
     *  name for who is calling, and it has neither the session's folder nor its model. */
    override fun call(tool: String, arguments: JsonObject, session: String?): JsonObject {
        val asked = ModeSession(session.orEmpty(), null, head.orEmpty(), "", ModeCaller.HEAD)
        val call = ModeCall(tool, arguments, asked, store)
        return when (val run = ModeFailure.run(mode.name) { mode.handler.answer(call) }) {
            is ModeRun.Threw -> result(run.said, failed = true)
            is ModeRun.Done -> result(run.value.text, failed = run.value is ModeAnswer.Error)
        }
    }

    override fun resources(): JsonArray = JsonArray(
        mode.resources.map { resource ->
            buildJsonObject {
                put("uri", resource.uri)
                put("name", resource.name)
                put("mimeType", resource.mimeType)
                resource.meta?.let { put("_meta", it) }
            }
        },
    )

    /** The resource's content: its file's, or what the handler gives for one the manifest declares with no file. A
     *  handler that throws has no content to give, and the client is told the resource is not there. */
    override fun read(uri: String): JsonArray? {
        val resource = mode.resources.firstOrNull { it.uri == uri } ?: return null
        val given = resource.content ?: when (val run = ModeFailure.run(mode.name) { mode.handler.resource(uri) }) {
            is ModeRun.Threw -> null
            is ModeRun.Done -> run.value
        }
        val text = given ?: return null
        return JsonArray(
            listOf(
                buildJsonObject {
                    put("uri", uri)
                    put("mimeType", resource.mimeType)
                    put("text", text)
                    resource.meta?.let { put("_meta", it) }
                },
            ),
        )
    }

    /** The mode's prompt.md: what a door mode adds to the model's request, an mcp mode's client is told at
     *  initialize. */
    override fun instructions(): String? = mode.prompt

    private fun result(text: String, failed: Boolean): JsonObject = buildJsonObject {
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                ),
            ),
        )
        put("isError", failed)
    }
}
