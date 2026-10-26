// NEW: MCP hub step 6 (docs/specs/mcp-hub.md; splice-lead, Oct 12, after proofs (e) and (f) passed) — the launch
// side. Every server Claude Code would load for the launch (step 1's plan) that the hub runs, shared or one per
// session, goes in one owner-only `--mcp-config` file as a hub address under its own name. A same-named entry replaces
// the user, local or approved project server Claude Code would have started (proof e), so no strict mode, and his
// claude.ai connectors stay his (the President's ruling). A plugin's servers do not yield to a same-named entry (proof
// a), so a plugin whose every declared server the hub runs is loaded with `--plugin-dir-no-mcp <its install folder>`:
// its MCP off, its skills and commands under their own names (proof f). That flag is hidden, and an unknown flag can
// stop a launch, so it is passed only on a Claude Code version where it is known to hold; a plugin without the flag
// keeps its servers as Claude Code starts them, since an entry beside the plugin's own would only add a copy. When the
// front does not answer, the launch carries no hub file and no plugin flag, and says so in one line: Claude Code
// starts its own. Before any front has run on this machine (no front.json), the hub is not set up and nothing is said.
package splice.client.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.client.Keys
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

// why: the header the front names a call's session by (HubLaunch.kt); the value is the launch id. McpKeyHolders knows a
// launch file's entries as splice's by it.
internal const val HUB_LAUNCH_HEADER = "X-Splice-Launch"
private const val HUB_PLUGIN_FLAG = "--plugin-dir-no-mcp"
private const val HUB_LAUNCH_FILE_PREFIX = "launch-"
private const val HUB_LAUNCH_FILE_SUFFIX = ".json"
private const val HUB_FRONT_HOST = "127.0.0.1"
private const val HUB_FRONT_STATE = "front.json"
private const val HUB_UNREACHED =
    "this session starts its own MCP servers: the MCP hub is not answering, or its launch file could not be written"
private const val HUB_UNREACHED_OVERLAY =
    "this session reaches splice's hosted MCP servers directly and starts the rest itself: the MCP hub is not answering"

// why: the front is a local process on loopback; one that has not accepted in this long is not serving.
public const val HUB_FRONT_CONNECT_MS: Int = 250

/** One launch the hub file is written for. [configDir] is the config home Claude Code reads, [folder] the folder it
 *  starts in, [launch] its id: the pid the launcher records and then execs Claude Code in. [claude] is the binary the
 *  launch execs. [overlayHead] is the head key of a launch through the wrapped `claude`, which falls back to the
 *  V4-445 overlay when the hub carries nothing; null for a head's own launch, whose home already carries its
 *  entries. */
public data class HubLaunchFor(
    val configDir: Path,
    val folder: Path?,
    val launch: String?,
    val claude: Path?,
    val overlayHead: String? = null,
)

/** What a launch adds to its argv, and one line for the person when the hub could not carry it. */
public data class HubLaunchArgs(val args: List<String>, val warning: String? = null)

/** Whether `--plugin-dir-no-mcp` holds on one Claude Code version, as the daemon's own per-version check recorded. */
public fun interface PluginFlagHolds {
    public fun holds(version: String): Boolean
}

/** Whether the daemon runs the MCP hub now, the verdict /health reports. A front.json left from an earlier run and a
 *  listener on the port are not a hub: with the hub off, a launch offers no hub address. */
public fun interface McpHubLive {
    public fun live(): Boolean
}

/** The port the hub front listens on, on loopback, and [dir], `<state>/mcp-hub`, where it keeps its state and the
 *  launch files live. [procRoot] says which launches still run. [hub] is the daemon's on or off for the hub. */
public data class McpHubFront(
    val port: Int,
    val dir: Path,
    val procRoot: Path = Path.of("/proc"),
    val hub: McpHubLive = McpHubLive { true },
)

/** The Claude Code version proof (f) ran on, where the flag holds with no recorded check, and the recorded checks. */
public data class PluginFlagGate(val tested: String, val checked: PluginFlagHolds = PluginFlagHolds { false })

public class McpHubLaunch(
    private val planner: McpLaunchPlanner,
    private val sharing: McpSharing,
    private val bearer: McpBearer,
    private val front: McpHubFront,
    private val log: LogSink,
    private val flag: PluginFlagGate,
    /** The V4-445 file a wrapped launch carries when the hub carries nothing. */
    private val overlay: HostedMcpOverlay? = null,
) {
    private val dir: Path get() = front.dir
    private val frontPort: Int get() = front.port
    private val procRoot: Path get() = front.procRoot

    public fun args(launch: HubLaunchFor): HubLaunchArgs {
        val hub = hubArgs(launch)
        if (hub.args.isNotEmpty()) return hub
        val fallback = fallback(launch)
        // the overlay still sends his hosted user-scope servers to splice, so the line says only what is true of them
        val warning = hub.warning?.let { if (fallback.isEmpty()) it else HUB_UNREACHED_OVERLAY }
        return HubLaunchArgs(fallback, warning)
    }

    private fun fallback(launch: HubLaunchFor): List<String> {
        val head = launch.overlayHead
        if (launch.folder == null) {
            return (
                head?.let { overlay?.args(it) }
                    ?: overlay?.managedArgs(launch.configDir.fileName.toString())
                ).orEmpty()
        }
        val selected = planner.plan(launch.configDir, launch.folder).servers
            .firstOrNull { it.name == TEMPLE_MCP_NAME }?.let { server ->
                McpServerInput(server.name, server.entry, server.provenance)
            }
        return if (head == null) {
            overlay?.managedArgs(launch.configDir.fileName.toString(), selected).orEmpty()
        } else {
            overlay?.args(head, selected).orEmpty()
        }
    }

    private fun hubArgs(launch: HubLaunchFor): HubLaunchArgs {
        val id = launch.launch?.takeIf(::isPid)
        val folder = launch.folder
        return if (id == null || folder == null) HubLaunchArgs(emptyList()) else planned(launch, id, folder)
    }

    /** The hub file and plugin flags for a launch whose id and folder are known. */
    private fun planned(launch: HubLaunchFor, id: String, folder: Path): HubLaunchArgs {
        val plan = planner.plan(launch.configDir, folder)
        val classes = sharing.plan(
            plan.servers.map { server ->
                McpServerInput(server.name, server.entry, server.provenance)
            },
        )
        val runs = plan.servers.map { it.name }.filter { classes.classOf(it) != McpShareClass.OWN }.toSet()
        val plugins = if (flagHolds(launch.claude)) pluginsHeld(plan, runs) else emptyMap()
        val ownPlugin = plan.servers.filter { it.pluginRoot != null && it.pluginRoot !in plugins }.map { it.name }
        val held = runs - ownPlugin.toSet()
        val frontRan = front.hub.live() && Files.exists(dir.resolve(HUB_FRONT_STATE))
        if (held.isEmpty() || !frontRan) return HubLaunchArgs(emptyList())
        val file = (if (frontAnswers()) write(id, held, ownTemple(plan, classes)) else null)
            ?: return HubLaunchArgs(emptyList(), HUB_UNREACHED)
        val flags = plugins.values.sorted().flatMap { listOf(HUB_PLUGIN_FLAG, it) }
        return HubLaunchArgs(listOf("--mcp-config", file.toString()) + flags)
    }

    private fun ownTemple(plan: McpLaunchPlan, classes: McpPlan): JsonObject {
        val own = plan.servers.firstOrNull {
            it.name == TEMPLE_MCP_NAME && classes.classOf(it.name) == McpShareClass.OWN
        }
        return JsonObject(own?.let { mapOf(it.name to it.entry) }.orEmpty())
    }

    /** Each plugin whose every declared server, loaded or left out, the hub runs: its install folder, by itself. */
    private fun pluginsHeld(plan: McpLaunchPlan, runs: Set<String>): Map<Path, String> {
        val declared = plan.servers.mapNotNull { server -> server.pluginRoot?.let { it to server.name } } +
            plan.leftOut.mapNotNull { server -> server.pluginRoot?.let { it to server.name } }
        return declared.groupBy({ it.first }, { it.second })
            .filterValues { names -> names.all { it in runs } }
            .keys.associateWith { it.toString() }
    }

    /** The binary's version is its real file's name (`~/.local/share/claude/versions/<version>`); a binary that does
     *  not resolve to one is a version no check covers. */
    private fun flagHolds(claude: Path?): Boolean {
        val version = try {
            claude?.toRealPath()?.fileName?.toString()
        } catch (_: IOException) {
            null
        } ?: return false
        return version == flag.tested || flag.checked.holds(version)
    }

    private fun isPid(text: String): Boolean = text.isNotEmpty() && text.all(Char::isDigit)

    private fun frontAnswers(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HUB_FRONT_HOST, frontPort), HUB_FRONT_CONNECT_MS) }
        true
    } catch (_: IOException) {
        false
    }

    /** The launch's file, owner-only because each entry carries the MCP bearer; the files of launches whose
     *  process is gone are removed first. */
    private fun write(id: String, held: Set<String>, own: JsonObject): Path? {
        // An access key that cannot be read leaves the launch on its own servers: the hub-unreached answer.
        val key = try {
            bearer()
        } catch (_: IOException) {
            return null
        }
        val body = buildJsonObject {
            putJsonObject(Keys.MCP_SERVERS) {
                held.sorted().forEach { name ->
                    putJsonObject(name) {
                        put("type", "http")
                        put("url", "http://$HUB_FRONT_HOST:$frontPort/mcp/$name")
                        putJsonObject("headers") {
                            put("Authorization", "Bearer $key")
                            put(HUB_LAUNCH_HEADER, id)
                        }
                    }
                }
                own.forEach { (name, entry) -> put(name, entry) }
            }
        }
        val file = dir.resolve("$HUB_LAUNCH_FILE_PREFIX$id$HUB_LAUNCH_FILE_SUFFIX")
        return try {
            sweep()
            SecureFile.writeAtomic0600(file, body.toString())
            file
        } catch (unwritable: IOException) {
            log("[mcp-hub] launch $id runs its own servers: ${SafeFailureText.render(unwritable)}\n")
            null
        }
    }

    private fun sweep() {
        if (!Files.isDirectory(dir)) return
        Files.newDirectoryStream(dir, "$HUB_LAUNCH_FILE_PREFIX*$HUB_LAUNCH_FILE_SUFFIX").use { files ->
            files.forEach { file ->
                val pid = file.fileName.toString().removePrefix(HUB_LAUNCH_FILE_PREFIX)
                    .removeSuffix(HUB_LAUNCH_FILE_SUFFIX)
                if (isPid(pid) && !Files.exists(procRoot.resolve(pid))) Files.deleteIfExists(file)
            }
        }
    }
}
