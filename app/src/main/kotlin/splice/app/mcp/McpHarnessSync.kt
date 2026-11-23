// Plans Splice's unified MCP server catalog (registry + ~/.claude.json + registered services) and syncs it
// to every harness the user chose to connect: Claude Code / Desktop, Antigravity (agy), and Codex.
package splice.app.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.MCP_HUB_FRONT_PORT
import splice.app.McpGlobalRead
import splice.app.cli.AdminSupport
import splice.client.mcp.HUB_FRONT_CONNECT_MS
import splice.client.mcp.McpAccessKey
import splice.client.mcp.McpBearer
import splice.client.mcp.McpPlan
import splice.client.mcp.McpServerInput
import splice.client.mcp.McpSharing
import splice.client.mcp.TempleMcpEntry
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpUserRegistry
import splice.core.topology.DaemonConfig
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.topology.TopologyLoader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

/** Outcome of syncing one harness to Splice MCP. */
internal data class HarnessSyncOutcome(
    val harness: McpHarness,
    val ok: Boolean,
    val changed: Boolean,
    val serverCount: Int,
    val hostedCount: Int,
    val detail: String,
)

/** Summary of a multi-harness sync pass. */
internal data class McpSyncReport(
    val serverCount: Int,
    val hostedCount: Int,
    val endpointPrefix: String,
    val outcomes: List<HarnessSyncOutcome>,
) {
    val allOk: Boolean get() = outcomes.all { it.ok }
}

/** Probe for whether the MCP hub front listens on loopback. */
internal fun interface LoopbackPortProbe {
    operator fun invoke(port: Int): Boolean
}

/** Strategy for updating Codex via `codex app-server` RPC; returns null on success or an error message. */
internal fun interface CodexRpcSync {
    suspend operator fun invoke(codexHome: Path, entries: Map<String, JsonObject>): String?
}

/** Injectable runtime seams for [McpHarnessSync], keeping constructor arity compact and tests hermetic. */
internal data class McpHarnessSyncSeams(
    val osName: String = System.getProperty("os.name").orEmpty(),
    val frontReachable: LoopbackPortProbe = LoopbackPortProbe { port ->
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), HUB_FRONT_CONNECT_MS) }
            true
        } catch (_: IOException) {
            false
        }
    },
    val codexRpc: CodexRpcSync? = null,
    val bearerOverride: McpBearer? = null,
)

/** Coordinates planning and syncing Splice MCP servers across Claude, Antigravity (agy), and Codex. */
internal class McpHarnessSync(
    private val home: Path,
    private val paths: StatePaths,
    private val env: EnvReader = EnvReader(System::getenv),
    private val log: LogSink = LogSink {},
    private val seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
) {
    private val discovery = McpHarnessDiscovery(home, env, seams.osName)
    private val agyWriter = AgyMcpConfigWriter(paths)
    private val codexFallback = CodexMcpTomlWriter(paths)
    private val registry = McpUserRegistry(paths)

    /** Syncs all Splice MCP servers to each harness in [harnesses] and remembers connected harnesses. */
    suspend fun sync(harnesses: Set<McpHarness>): McpSyncReport {
        val endpointPrefix = activeEndpointPrefix()
        val globalRead = McpGlobalRead(home, log, TempleMcpEntry(home, env, paths), registry)
        val inputs = globalRead.inputs()
        val sharing = buildSharing(endpointPrefix)
        val basePlan = sharing.plan(inputs)
        val outcomes = harnesses.sortedBy { it.wire }.map { harness ->
            syncOne(harness, inputs, sharing, basePlan)
        }
        val succeeded = outcomes.filter { it.ok }.map { it.harness }.toSet()
        if (succeeded.isNotEmpty()) {
            registry.rememberHarnesses(succeeded)
        }
        return McpSyncReport(
            serverCount = inputs.size,
            hostedCount = basePlan.hosted.size,
            endpointPrefix = endpointPrefix,
            outcomes = outcomes,
        )
    }

    private suspend fun syncOne(
        harness: McpHarness,
        inputs: List<McpServerInput>,
        sharing: McpSharing,
        basePlan: McpPlan,
    ): HarnessSyncOutcome = when (harness) {
        McpHarness.CLAUDE -> HarnessSyncOutcome(
            harness = McpHarness.CLAUDE,
            ok = true,
            changed = false,
            serverCount = inputs.size,
            hostedCount = basePlan.hosted.size,
            detail = "Claude sessions load Splice MCP servers automatically through Splice's launch overlay",
        )
        McpHarness.AGY -> syncAgy(inputs, sharing)
        McpHarness.CODEX -> syncCodex(inputs, sharing)
    }

    private fun syncAgy(inputs: List<McpServerInput>, sharing: McpSharing): HarnessSyncOutcome {
        val plan = sharing.plan(inputs, McpHarness.AGY.wire)
        val entries = plan.rewritten.entries.mapNotNull { (name, elem) ->
            (elem as? JsonObject)?.let { name to it }
        }.toMap()
        val target = discovery.agyConfigFile()
        return try {
            val changed = agyWriter.sync(target, entries)
            val verb = if (changed) "updated" else "already up to date"
            HarnessSyncOutcome(
                harness = McpHarness.AGY,
                ok = true,
                changed = changed,
                serverCount = entries.size,
                hostedCount = plan.hosted.size,
                detail = "$target ($verb)",
            )
        } catch (failure: IOException) {
            HarnessSyncOutcome(
                harness = McpHarness.AGY,
                ok = false,
                changed = false,
                serverCount = entries.size,
                hostedCount = plan.hosted.size,
                detail = "could not write $target (${failure::class.simpleName})",
            )
        }
    }

    private suspend fun syncCodex(inputs: List<McpServerInput>, sharing: McpSharing): HarnessSyncOutcome {
        val plan = sharing.plan(inputs, McpHarness.CODEX.wire)
        val codexEntries = LinkedHashMap<String, JsonObject>()
        plan.rewritten.forEach { (name, elem) ->
            val obj = elem as? JsonObject ?: return@forEach
            toCodexEntry(obj)?.let { codexEntries[name] = it }
        }
        val codexHome = discovery.codexHomeDir()
        val configFile = discovery.codexConfigFile()
        val rpc = seams.codexRpc ?: CodexRpcSync { dir, map -> NativeCodexMcp(paths, log).installAll(dir, map) }
        val rpcError = rpc(codexHome, codexEntries)
        if (rpcError == null) {
            return HarnessSyncOutcome(
                harness = McpHarness.CODEX,
                ok = true,
                changed = true,
                serverCount = codexEntries.size,
                hostedCount = plan.hosted.size,
                detail = "$configFile (synced via Codex config API)",
            )
        }
        return syncCodexFallback(configFile, codexEntries, plan.hosted.size)
    }

    private fun syncCodexFallback(
        configFile: Path,
        codexEntries: Map<String, JsonObject>,
        hostedCount: Int,
    ): HarnessSyncOutcome = try {
        val changed = codexFallback.sync(configFile, codexEntries)
        val verb = if (changed) "updated" else "already up to date"
        HarnessSyncOutcome(
            harness = McpHarness.CODEX,
            ok = true,
            changed = changed,
            serverCount = codexEntries.size,
            hostedCount = hostedCount,
            detail = "$configFile ($verb)",
        )
    } catch (failure: IOException) {
        HarnessSyncOutcome(
            harness = McpHarness.CODEX,
            ok = false,
            changed = false,
            serverCount = codexEntries.size,
            hostedCount = hostedCount,
            detail = "could not update $configFile (${failure::class.simpleName})",
        )
    }

    /** Converts a Splice-planned canonical MCP entry into Codex's `mcp_servers.<name>` shape. */
    fun toCodexEntry(canonical: JsonObject): JsonObject? {
        val url = JsonScalars.str(canonical, "url")
        val command = JsonScalars.str(canonical, "command")
        return when {
            !url.isNullOrBlank() -> buildJsonObject {
                put("url", url)
                val headers = canonical["headers"] as? JsonObject
                if (headers != null && headers.isNotEmpty()) put("http_headers", headers)
            }
            !command.isNullOrBlank() -> buildJsonObject {
                put("command", command)
                (canonical["args"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { put("args", it) }
                JsonScalars.str(canonical, "cwd")?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
                (canonical["env"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put("env", it) }
            }
            else -> null
        }
    }

    private fun activeEndpointPrefix(): String {
        val frontState = paths.stateDir.resolve("mcp-hub").resolve("front.json")
        val useFront = Files.isRegularFile(frontState) && seams.frontReachable(MCP_HUB_FRONT_PORT)
        val port = if (useFront) MCP_HUB_FRONT_PORT else AdminSupport.controlPort(env)
        return "http://127.0.0.1:$port/mcp/"
    }

    private fun buildSharing(endpointPrefix: String): McpSharing {
        val daemon = readDaemonConfig()
        val enabled = daemon?.mcpHosting ?: true
        val exclude = daemon?.mcpHostingExclude?.toSet().orEmpty()
        val bearer = seams.bearerOverride ?: defaultBearer()
        return McpSharing(
            enabled = enabled,
            exclude = exclude,
            endpointPrefix = endpointPrefix,
            bearer = bearer,
        )
    }

    private fun defaultBearer(): McpBearer {
        val mgmtKey = MgmtKey(paths)
        return McpAccessKey(
            stateDir = paths.stateDir,
            managementFile = mgmtKey.file,
            log = log,
            source = McpBearer { mgmtKey.getOrCreate() },
        )
    }

    private fun readDaemonConfig(): DaemonConfig? = try {
        val path = TopologyLoader.configPath(env)
        if (Files.isRegularFile(path)) TopologyLoader.parse(Files.readString(path)).daemon else null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
