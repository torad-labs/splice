// NEW: modes-mcp (Nov 21, 2026) — the mcp modes as MCP servers the daemon answers itself (modes/README.md, `kind`).
// A door mode's tools ride the model's request and are answered inside the turn; an mcp mode is a server the client
// lists and calls as any other, so its tools reach the client's own tool surface and its `ui://` resources reach the
// client that can draw them. The MCP host asks this for a server by name, head and caller (InProcessMcp), and gets one
// only when a mode of that `server` name is on for that head: an off mode's name is nobody's, as it is in the launch
// entry. The caller is the one the client's entry names (OwnMcpLaunch, and the stdio bridge for Claude Desktop), and
// the mode is told it, or told none for a request that names none. A person's own server of a mode's name keeps it.
package splice.app.modes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.OwnMcpServers
import splice.control.mcp.inprocess.InProcessMcp
import splice.control.mcp.inprocess.InProcessMcpServer
import splice.modes.api.FoundMode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeKind
import splice.modes.api.ModeSession
import splice.modes.api.ModeStore

/** The modes the daemon holds now, or null before boot assigned them and in a daemon that carries none. */
internal fun interface ModesNow {
    fun held(): DaemonModes?
}

/** The mcp modes that are on, each as the server its manifest names: the server a client calls, and the names a
 *  launch or Claude Desktop's configuration lists. */
internal class ModeMcp(private val modes: ModesNow) : InProcessMcp, OwnMcpServers {
    /** The mcp modes on for [head] and offered to [caller] (a manifest's word for one: `door`, `head` or `desktop`),
     *  in name order: the servers a launch there lists for its client. A caller no manifest names is offered none, and
     *  a mode whose server name is one of the person's own servers is not listed: the name is theirs. */
    fun on(head: String, caller: String): List<FoundMode> {
        val held = modes.held() ?: return emptyList()
        val asking = ModeCaller.entries.firstOrNull { it.wire == caller } ?: return emptyList()
        return held.catalog.modes.filter { mode ->
            mode.manifest.kind == ModeKind.MCP && held.state(mode, head, asking).on
        }.filterNot(held::taken)
    }

    /** The servers of the mcp modes on for [head] and offered to [caller], by name. */
    override fun names(head: String, caller: String): List<String> = on(head, caller).map { it.manifest.server }

    /** The mode serving [name] for a client on [head]. A request that names a caller (OwnMcpLaunch's entries, the
     *  bridge for Claude Desktop) is served where the mode is on for that caller, `[callers] off` and `on_for`
     *  included; one that names none, or a word no manifest uses, is from a caller that is not known (a host reading a
     *  page, an operator's tool), is answered while the mode is on for any caller, and the mode is told no caller. A
     *  mode whose server name is one of the person's own servers is not served: the address is theirs
     *  (DaemonModes.taken). */
    override fun server(name: String, head: String?, caller: String?): InProcessMcpServer? {
        val held = modes.held() ?: return null
        val mode = held.catalog.modes.firstOrNull { it.manifest.kind == ModeKind.MCP && it.manifest.server == name }
            ?: return null
        val asking = ModeCaller.entries.firstOrNull { it.wire == caller }
        // a caller not known is answered for whichever caller the mode is on for: the name grants nothing either way
        val callers = asking?.let(::listOf) ?: (listOf(null) + mode.manifest.offer.onFor)
        val offered = callers.any { by ->
            if (head == null) held.everyHead(mode, by).on else held.state(mode, head, by).on
        }
        return if (offered && !held.taken(mode)) ModeMcpServer(mode, held.store(mode.name), head, asking) else null
    }
}

/** One mcp mode as its server: its tools and resources as its folder writes them, and its handler's answers. */
internal class ModeMcpServer(
    private val mode: FoundMode,
    private val store: ModeStore,
    private val head: String?,
    /** Who the request says is calling, which the mode is told; null when it says nothing. */
    private val caller: ModeCaller?,
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
     *  name for the session, and it has neither the session's folder nor its model. */
    override suspend fun call(tool: String, arguments: JsonObject, session: String?): JsonObject {
        val asked = ModeSession(session.orEmpty(), null, head.orEmpty(), "", caller)
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
