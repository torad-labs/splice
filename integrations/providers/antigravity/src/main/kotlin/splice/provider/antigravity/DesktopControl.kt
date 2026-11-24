// Dispatches Claude Desktop control requests for DesktopSession (initialize, set_model, interrupt, mcp_*, settings,
// reload_skills).
package splice.provider.antigravity

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.provider.antigravity.skills.SkillShelf

/** Mutable session hooks exposed to [DesktopControl]. */
internal interface SessionControlHost {
    val agy: AgySession?
    val commands: List<AgyCommand>

    /** The session's local skills, held from its start whether or not agy has opened. */
    val skills: SkillShelf

    /** The frames a control answer is built with, whether or not agy has opened. */
    val frames: DesktopFrames

    /** The names of agy's own slash commands, which a local skill of the same name never shadows. */
    val nativeCommands: Set<String>
    suspend fun ensureOpen(desktopSpecs: List<AgyMcpSpec>): DesktopParts
    fun agy(message: JsonObject)
    fun syncLook(model: String? = null, mode: String? = null)

    /** Says the session's `system/init` now. */
    fun announce()
    fun cancelOrSwitch(switchOut: Boolean)
}

/** Handles `control_request` payloads from Claude Desktop for one agy session. */
internal class DesktopControl(
    private val host: SessionControlHost,
    private val coordinator: AgyMcpCoordinator,
    private val mcpState: McpState,
    private val lists: DesktopLists,
) {
    suspend fun handle(id: String, request: JsonObject): JsonObject? {
        val subtype = JsonScalars.str(request, "subtype")
        return when (subtype) {
            "initialize" -> {
                // The answer, then `system/init`, so Desktop has the session's commands and skills before any turn;
                // the first turn does not say it again.
                val p = host.ensureOpen(coordinator.readInitializeSpecs(request))
                p.out(p.frames.initialized(id, host.commands, lists.models(host.agy?.catalog), mcpState.applied))
                host.announce()
                null
            }
            "set_model" -> setModel(id, request)
            "reload_skills" -> reloadSkills(id)
            "mcp_status" -> host.ensureOpen(emptyList()).frames.controlOk(id, mcpState.statusResponse())
            "mcp_set_servers" -> {
                val p = host.ensureOpen(emptyList())
                val sink = object : AgyMessageSink {
                    override fun accept(message: JsonObject) = host.agy(message)
                }
                val body = coordinator.setServers(host.agy, request, sink)
                host.syncLook()
                p.frames.controlOk(id, body)
            }
            else -> otherControl(id, subtype, request)
        }
    }

    private suspend fun setModel(id: String, request: JsonObject): JsonObject? {
        val requested = JsonScalars.str(request, "model").orEmpty()
        if (requested.isNotEmpty() && !requested.startsWith(ANTIGRAVITY_MODEL_PREFIX)) {
            host.cancelOrSwitch(switchOut = true)
            return null
        }
        val p = host.ensureOpen(emptyList())
        val refusal = host.agy?.model(requested)
        if (refusal == null) host.syncLook(model = requested)
        return refusal?.let { p.frames.controlError(id, it) } ?: p.frames.controlOk(id)
    }

    private suspend fun otherControl(id: String, subtype: String?, request: JsonObject): JsonObject {
        val p = host.ensureOpen(emptyList())
        return when (subtype) {
            "interrupt" -> {
                host.cancelOrSwitch(switchOut = false)
                p.frames.controlOk(id)
            }
            "set_permission_mode", "apply_flag_settings" -> applySetting(p, id, request)
            else -> p.frames.controlError(id, "$subtype is not offered by a session on agy")
        }
    }

    /** Scans the skill folders again and answers with what it found, as Claude Code does: the local skills and
     *  commands agy's own don't shadow. It never opens agy, which opens on `initialize` with Desktop's MCP servers. */
    private fun reloadSkills(id: String): JsonObject {
        val native = host.nativeCommands
        val fresh = host.skills.reload().typed.filter { it.name !in native }
        val listed = host.frames.commandsOf(lists.mergeCommands(emptyList(), fresh))
        return host.frames.controlOk(id, JsonObject(mapOf("skills" to listed)))
    }

    private suspend fun applySetting(p: DesktopParts, id: String, request: JsonObject): JsonObject {
        val mode = JsonScalars.str(request, "mode")
        val refusal = if (mode != null) {
            val blocked = p.look.permissionMode == BYPASS_MODE && mode != BYPASS_MODE
            val res = if (blocked) STARTED_BYPASSING_MSG else host.agy?.mode(mode)
            if (res == null) host.syncLook(mode = mode)
            res
        } else {
            JsonScalars.str(request["settings"] as? JsonObject, "effortLevel")?.let { host.agy?.effort(it) }
        }
        return refusal?.let { p.frames.controlError(id, it) } ?: p.frames.controlOk(id)
    }
}

private const val STARTED_BYPASSING_MSG =
    "this session's agy was started without permission prompts; start a new session to have them"
