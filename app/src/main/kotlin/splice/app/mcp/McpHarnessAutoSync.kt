// Automatic MCP harness synchronization in the daemon lifecycle: runs once at daemon startup and
// watches `~/.claude.json` (plus Splice's registry and Temple registration) for changes with a
// debounced stat/mtime loop. Strictly opt-in: only harnesses already recorded in `McpUserRegistry`
// are synced; unconnected harnesses (including `agy` when not opted in) are never touched.
package splice.app.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.app.McpGlobalRead
import splice.client.mcp.McpServerInput
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpUserRegistry
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.Ticker
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import splice.upstream.codemode.ProcessWaiter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: 2 seconds keeps `~/.claude.json` edits responsive across harnesses while doing only three stat calls per tick.
private const val MCP_WATCH_POLL_MS = 2_000L

// why: 500ms coalesces rapid atomic write-temp-and-rename bursts from Claude Code before re-reading and syncing.
private const val MCP_WATCH_DEBOUNCE_MS = 500L

/** Keeps connected harnesses synchronized with `~/.claude.json`, the Splice registry, and Temple at boot and on edits. */
internal class McpHarnessAutoSync(
    private val home: Path,
    private val paths: StatePaths,
    private val env: EnvReader = paths.envReader,
    private val log: LogSink = LogSink {},
    private val seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
    private val ticker: Ticker = ProcessTicker(),
    private val waiter: Waiter = ProcessWaiter(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val syncLock = Mutex()
    private val io = ProcessDispatchers().io()
    private val registry = McpUserRegistry(paths)
    private val discovery = McpHarnessDiscovery(home, env, seams.osName)
    private val templeFile = TempleMcpEntry(home, env, paths).registrationFile
    private var lastStamps: List<FileStamp?> = emptyList()
    private var lastFingerprint: SyncFingerprint? = null

    /** Runs one startup sync and then watches source files in [scope] until the daemon cancels the scope. */
    fun watch(scope: CoroutineScope) {
        scope.launch {
            runSafe("startup") { syncOnStartup() }
            while (ticker.awaitTick(MCP_WATCH_POLL_MS)) {
                runSafe("watch") { checkForChanges() }
            }
        }
    }

    /** Performs one startup synchronization pass for harnesses already recorded as connected. */
    suspend fun syncOnStartup(): Boolean = syncLock.withLock {
        withContext(io) {
            val stamps = readStamps()
            val connected = registry.read().harnesses
            if (connected.isEmpty()) {
                lastStamps = stamps
                lastFingerprint = null
                false
            } else {
                executeSync(connected, stamps, reason = "startup")
            }
        }
    }

    /** Checks watched file stamps and triggers a debounced sync only when effective MCP inputs or harnesses changed. */
    suspend fun checkForChanges(): Boolean = syncLock.withLock {
        val current = withContext(io) { readStamps() }
        if (current == lastStamps) return@withLock false
        waiter.wait(MCP_WATCH_DEBOUNCE_MS)
        withContext(io) {
            val settled = readStamps()
            val connected = registry.read().harnesses
            if (connected.isEmpty()) {
                lastStamps = settled
                lastFingerprint = null
                false
            } else {
                syncIfInputsChanged(connected, settled)
            }
        }
    }

    private suspend fun syncIfInputsChanged(connected: Set<McpHarness>, settled: List<FileStamp?>): Boolean {
        if (!isClaudeJsonReadable()) {
            lastStamps = settled
            log("[mcp-sync] skipped: global MCP configuration is unreadable or malformed\n")
            return false
        }
        val fingerprint = readFingerprint(connected)
        if (fingerprint == lastFingerprint) {
            lastStamps = settled
            return false
        }
        return executeSync(connected, settled, reason = "change")
    }

    private suspend fun executeSync(
        connected: Set<McpHarness>,
        stamps: List<FileStamp?>,
        reason: String,
    ): Boolean {
        if (!isClaudeJsonReadable()) {
            lastStamps = stamps
            log("[mcp-sync] $reason skipped: global MCP configuration is unreadable or malformed\n")
            return false
        }
        val fingerprint = readFingerprint(connected)
        val report = McpHarnessSync(home, paths, env, log, seams).sync(connected)
        lastStamps = stamps
        if (report.allOk) lastFingerprint = fingerprint
        logReport(reason, report)
        return report.outcomes.any { it.changed }
    }

    private fun logReport(reason: String, report: McpSyncReport) {
        val updated = report.outcomes.filter { it.ok && it.changed }.map { it.harness.wire }
        if (updated.isNotEmpty()) {
            log("[mcp-sync] $reason: synced ${updated.joinToString(", ")} (${report.serverCount} server(s))\n")
        }
        val failed = report.outcomes.filter { !it.ok }.map { "${it.harness.wire} (${it.detail})" }
        if (failed.isNotEmpty()) {
            log("[mcp-sync] $reason: failed for ${failed.joinToString("; ")}\n")
        }
    }

    private fun readFingerprint(connected: Set<McpHarness>): SyncFingerprint {
        val inputs = McpGlobalRead(home, log, TempleMcpEntry(home, env, paths), registry).inputs()
        val entries = inputs.sortedBy(McpServerInput::name).map { it.name to it.entry }
        return SyncFingerprint(connected, entries)
    }

    private fun isClaudeJsonReadable(): Boolean {
        val file = discovery.claudeCodeFile()
        if (!Files.exists(file)) return true
        return try {
            val parsed = JsonScalars.objectOrNull(json, Files.readString(file))
            parsed != null && (parsed["mcpServers"] == null || parsed["mcpServers"] is JsonObject)
        } catch (_: IOException) {
            false
        }
    }

    private fun readStamps(): List<FileStamp?> =
        listOf(discovery.claudeCodeFile(), registry.file, templeFile).map(::stampOf)

    private fun stampOf(path: Path): FileStamp? = try {
        if (!Files.isRegularFile(path)) {
            null
        } else {
            FileStamp(Files.getLastModifiedTime(path), Files.size(path))
        }
    } catch (_: IOException) {
        null
    }

    private suspend inline fun runSafe(phase: String, block: () -> Unit) {
        Cancellables.discard(
            Cancellables.runCatchingCancellable { block() }.onFailure { failure ->
                log("[mcp-sync] $phase error (${SafeFailureText.render(failure)})\n")
            },
            "auto-sync failures are logged and the watcher stays alive",
        )
    }

    private data class FileStamp(val modified: FileTime, val size: Long)

    private data class SyncFingerprint(
        val harnesses: Set<McpHarness>,
        val entries: List<Pair<String, JsonElement>>,
    )
}
