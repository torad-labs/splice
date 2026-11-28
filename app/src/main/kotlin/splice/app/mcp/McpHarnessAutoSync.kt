// Automatic MCP harness synchronization in the daemon lifecycle: runs once at daemon startup and
// watches `~/.claude.json` (plus Splice's registry and Temple registration) for changes with a
// debounced stat/mtime loop. Strictly opt-in: only harnesses already recorded in `McpUserRegistry`
// are synced; unconnected harnesses (including `agy` when not opted in) are never touched.
// Shares the control plane's harness sync mutex so in-daemon manual syncs and auto-syncs serialize.
package splice.app.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import splice.client.mcp.McpServerInput
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpUserRegistry
import splice.core.util.Cancellables
import splice.core.util.EnvReader
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

// why: exponential retry backoff caps at 2^4 = 16 poll ticks (~32s) so persistent failures do not busy-loop.
private const val MCP_RETRY_MAX_SHIFT = 4

/** Timing seams for [McpHarnessAutoSync], keeping constructor arity compact and tests wall-clock-free. */
internal data class McpAutoSyncTiming(
    val ticker: Ticker = ProcessTicker(),
    val waiter: Waiter = ProcessWaiter(),
)

/** Keeps connected harnesses synchronized with `~/.claude.json`, the Splice registry, and Temple at boot and on edits. */
internal class McpHarnessAutoSync(
    private val home: Path,
    private val paths: StatePaths,
    private val env: EnvReader = paths.envReader,
    private val log: LogSink = LogSink {},
    private val seams: McpHarnessSyncSeams = McpHarnessSyncSeams(),
    private val syncLock: Mutex = Mutex(),
    private val timing: McpAutoSyncTiming = McpAutoSyncTiming(),
) {
    private val io = ProcessDispatchers().io()
    private val registry = McpUserRegistry(paths)
    private val discovery = McpHarnessDiscovery(home, env, seams.osName)
    private val snapshotReader = McpStrictSyncSnapshot(home, paths, env, registry)
    private val templeFile = TempleMcpEntry(home, env, paths).registrationFile
    private var lastStamps: List<FileStamp?> = emptyList()
    private var lastFingerprint: SyncFingerprint? = null
    private var pendingRetry: Boolean = false
    private var consecutiveFailures: Int = 0
    private var retryTicksRemaining: Int = 0

    /** Runs one startup sync and then watches source files in [scope] until the daemon cancels the scope. */
    fun watch(scope: CoroutineScope) {
        scope.launch {
            runSafe("startup") { syncOnStartup() }
            while (timing.ticker.awaitTick(MCP_WATCH_POLL_MS)) {
                runSafe("watch") { checkForChanges() }
            }
        }
    }

    /** Performs one startup synchronization pass for harnesses already recorded as connected. */
    suspend fun syncOnStartup(): Boolean = syncLock.withLock {
        withContext(io) {
            val stamps = readStamps()
            if (registry.read().harnesses.isEmpty()) {
                resetSettled(stamps, fingerprint = null)
                false
            } else {
                attemptSync(stamps, reason = "startup", force = true)
            }
        }
    }

    /** Checks watched file stamps and triggers a debounced sync on edits or bounded retry after a failed sync. */
    suspend fun checkForChanges(): Boolean = syncLock.withLock {
        val current = withContext(io) { readStamps() }
        val stampsMoved = current != lastStamps
        val retryDue = !stampsMoved && pendingRetry && --retryTicksRemaining <= 0
        if (!stampsMoved && !retryDue) return@withLock false
        if (stampsMoved) timing.waiter.wait(MCP_WATCH_DEBOUNCE_MS)
        withContext(io) {
            val settled = if (stampsMoved) readStamps() else current
            if (registry.read().harnesses.isEmpty()) {
                resetSettled(settled, fingerprint = null)
                false
            } else {
                attemptSync(settled, reason = if (stampsMoved) "change" else "retry", force = pendingRetry)
            }
        }
    }

    private suspend fun attemptSync(stamps: List<FileStamp?>, reason: String, force: Boolean): Boolean {
        val snapshot = readSnapshotOrRecordFailure(stamps, reason) ?: return false
        val fingerprint = SyncFingerprint(
            snapshot.harnesses,
            snapshot.inputs.sortedBy(McpServerInput::name).map { it.name to it.entry },
        )
        return when {
            snapshot.harnesses.isEmpty() -> {
                resetSettled(stamps, fingerprint = null)
                false
            }
            !force && fingerprint == lastFingerprint -> {
                lastStamps = stamps
                false
            }
            else -> runSync(snapshot, fingerprint, stamps, reason)
        }
    }

    private fun readSnapshotOrRecordFailure(stamps: List<FileStamp?>, reason: String): McpSyncSnapshot? = try {
        snapshotReader.read()
    } catch (failure: IOException) {
        recordFailure(stamps)
        log("[mcp-sync] $reason skipped: ${SafeFailureText.render(failure)}\n")
        null
    }

    private suspend fun runSync(
        snapshot: McpSyncSnapshot,
        fingerprint: SyncFingerprint,
        stamps: List<FileStamp?>,
        reason: String,
    ): Boolean = try {
        val report = McpHarnessSync(home, paths, env, log, seams).syncInputs(snapshot.harnesses, snapshot.inputs)
        if (report.allOk) resetSettled(stamps, fingerprint) else recordFailure(stamps)
        logReport(reason, report)
        report.outcomes.any { it.changed }
    } catch (failure: IOException) {
        recordFailure(stamps)
        log("[mcp-sync] $reason failed (${SafeFailureText.render(failure)})\n")
        false
    }

    private fun resetSettled(stamps: List<FileStamp?>, fingerprint: SyncFingerprint?) {
        lastStamps = stamps
        lastFingerprint = fingerprint
        pendingRetry = false
        consecutiveFailures = 0
        retryTicksRemaining = 0
    }

    private fun recordFailure(stamps: List<FileStamp?>) {
        lastStamps = stamps
        val shift = minOf(consecutiveFailures, MCP_RETRY_MAX_SHIFT)
        consecutiveFailures += 1
        retryTicksRemaining = 1 shl shift
        pendingRetry = true
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
        report.registrySaveError?.let { why ->
            log("[mcp-sync] $reason: failed to save connected harnesses ($why)\n")
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
