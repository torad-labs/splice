// Plans Splice's unified MCP server catalog (registry + ~/.claude.json + registered services) and syncs it
// to every harness the user chose to connect: Claude Code / Desktop (its gateway profiles, McpDesktopSync),
// Antigravity (agy), and Codex.
package splice.app.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.app.cli.peer.PeerMcpLaunch
import splice.client.mcp.McpPlan
import splice.client.mcp.McpServerInput
import splice.client.mcp.McpSharing
import splice.client.mcp.PEER_MCP_SERVER
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpUserRegistry
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Path

/** Coordinates planning and syncing Splice MCP servers across Claude, Antigravity (agy), and Codex. */
internal class McpHarnessSync(
    private val home: Path,
    private val paths: StatePaths,
    private val env: EnvReader = EnvReader(System::getenv),
    private val log: LogSink = LogSink {},
    private val seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
) {
    private val discovery = McpHarnessDiscovery(home, env, seams.osName)
    private val staleEntries = seams.agyPortProbe?.let(::AgyMcpStaleEntries) ?: AgyMcpStaleEntries()
    private val agyWriter = AgyMcpConfigWriter(paths, staleEntries, log)
    private val codexFallback = CodexMcpTomlWriter(paths)
    private val registry = McpUserRegistry(paths)
    private val snapshotReader = McpStrictSyncSnapshot(home, paths, env, registry)
    private val sharingFactory = McpHarnessSharing(paths, env, log, seams)
    private val urlAuth = McpUrlAuthNormalizer()

    /** Syncs all Splice MCP servers to each harness in [harnesses] and remembers connected harnesses. */
    suspend fun sync(harnesses: Set<McpHarness>): McpSyncReport =
        syncInputs(harnesses, snapshotReader.read().inputs)

    /** Syncs pre-read strict [inputs] (single snapshot without re-reading `~/.claude.json`) to [harnesses]. */
    suspend fun syncInputs(harnesses: Set<McpHarness>, inputs: List<McpServerInput>): McpSyncReport {
        val endpointPrefix = sharingFactory.endpointPrefix()
        val sharing = sharingFactory.planner(endpointPrefix)
        val basePlan = sharing.plan(inputs)
        val outcomes = harnesses.sortedBy { it.wire }.map { harness ->
            syncOne(harness, inputs, sharing, basePlan)
        }
        val succeeded = outcomes.filter { it.ok }.map { it.harness }.toSet()
        val registrySaveError = if (succeeded.isNotEmpty()) {
            try {
                registry.rememberHarnesses(succeeded)
                null
            } catch (failure: IOException) {
                failure::class.simpleName ?: "IOException"
            }
        } else {
            null
        }
        return McpSyncReport(inputs.size, basePlan.hosted.size, endpointPrefix, outcomes, registrySaveError)
    }

    private suspend fun syncOne(
        harness: McpHarness,
        inputs: List<McpServerInput>,
        sharing: McpSharing,
        basePlan: McpPlan,
    ): HarnessSyncOutcome = when (harness) {
        McpHarness.CLAUDE -> syncClaude(inputs, basePlan)
        McpHarness.AGY -> syncAgy(inputs, sharing)
        McpHarness.CODEX -> syncCodex(inputs, sharing)
    }

    /** Claude Code sessions load every server through Splice's launch overlay; Claude Desktop on the gateway is given
     *  them in each of its profiles (McpDesktopSync). */
    private fun syncClaude(inputs: List<McpServerInput>, basePlan: McpPlan): HarnessSyncOutcome {
        val profiles = McpDesktopSync(home, paths, env, seams.osName).sync()
        val desktop = profiles.joinToString(", ") { (profile, changed, failed) ->
            val verb = failed?.let { "could not write it ($it)" } ?: if (changed) "updated" else UP_TO_DATE
            "$profile ($verb)"
        }.ifEmpty { "no gateway profile found" }
        val detail = "Claude Code sessions load Splice MCP servers through Splice's launch overlay; " +
            "Claude Desktop: $desktop"
        return HarnessSyncOutcome(
            harness = McpHarness.CLAUDE,
            ok = profiles.none { it.failed != null },
            changed = profiles.any { it.changed },
            serverCount = inputs.size,
            hostedCount = basePlan.hosted.size,
            detail = detail,
        )
    }

    private fun syncAgy(inputs: List<McpServerInput>, sharing: McpSharing): HarnessSyncOutcome {
        val plan = sharing.plan(inputs, McpHarness.AGY.wire)
        val entries = plan.rewritten.entries.mapNotNull { (name, elem) ->
            (elem as? JsonObject)?.let { name to it }
        }.toMap()
        val target = discovery.agyConfigFile()
        return try {
            val changed = agyWriter.sync(target, entries)
            val verb = if (changed) "updated" else UP_TO_DATE
            HarnessSyncOutcome(McpHarness.AGY, true, changed, entries.size, plan.hosted.size, "$target ($verb)")
        } catch (failure: IOException) {
            val detail = "could not write $target (${failure::class.simpleName})"
            HarnessSyncOutcome(McpHarness.AGY, false, false, entries.size, plan.hosted.size, detail)
        }
    }

    private suspend fun syncCodex(inputs: List<McpServerInput>, sharing: McpSharing): HarnessSyncOutcome {
        val plan = sharing.plan(inputs, McpHarness.CODEX.wire)
        val codexEntries = LinkedHashMap<String, JsonObject>()
        plan.rewritten.forEach { (name, elem) ->
            val obj = elem as? JsonObject ?: return@forEach
            toCodexEntry(obj)?.let { codexEntries[name] = it }
        }
        if (McpHarness.CODEX.definition.peer?.autoSubscribe == true) {
            toCodexEntry(PeerMcpLaunch(env).entry())?.let { codexEntries[PEER_MCP_SERVER] = it }
        }
        val configFile = discovery.codexConfigFile()
        val rpc = seams.codexRpc ?: CodexRpcSync { dir, map ->
            NativeCodexMcp(paths, log).installAllWithStatus(dir, map)
        }
        val rpcResult = rpc(discovery.codexHomeDir(), codexEntries)
        if (rpcResult.ok) {
            val verb = if (rpcResult.changed) "synced" else UP_TO_DATE
            return HarnessSyncOutcome(
                McpHarness.CODEX,
                true,
                rpcResult.changed,
                codexEntries.size,
                plan.hosted.size,
                "$configFile ($verb via Codex config API)",
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
        val verb = if (changed) "updated" else UP_TO_DATE
        HarnessSyncOutcome(McpHarness.CODEX, true, changed, codexEntries.size, hostedCount, "$configFile ($verb)")
    } catch (failure: IOException) {
        val detail = "could not update $configFile (${failure::class.simpleName})"
        HarnessSyncOutcome(McpHarness.CODEX, false, false, codexEntries.size, hostedCount, detail)
    }

    /** Converts a Splice-planned canonical MCP entry into Codex's `mcp_servers.<name>` shape. */
    fun toCodexEntry(canonical: JsonObject): JsonObject? {
        val url = JsonScalars.str(canonical, "url")
        val command = JsonScalars.str(canonical, "command")
        return when {
            !url.isNullOrBlank() -> encodeCodexHttp(url, canonical["headers"] as? JsonObject)
            !command.isNullOrBlank() -> buildJsonObject {
                put("command", command)
                (canonical["args"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { put("args", it) }
                JsonScalars.str(canonical, "cwd")?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
                (canonical["env"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put("env", it) }
            }
            else -> null
        }
    }

    private fun encodeCodexHttp(url: String, headers: JsonObject?): JsonObject {
        val headerMap = LinkedHashMap<String, String>()
        headers?.forEach { (k, v) -> JsonScalars.str(v)?.let { headerMap[k] = it } }
        val normalized = urlAuth.normalize(url, headerMap)
        return buildJsonObject {
            put("url", normalized.url)
            if (normalized.headers.isNotEmpty()) {
                putJsonObject("http_headers") {
                    normalized.headers.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, v) }
                }
            }
        }
    }
}

// How a harness config that needed no change is named in its outcome.
private const val UP_TO_DATE = "already up to date"
