// NEW: server mode (docs/specs/server-mode.md, F14). Durable state snapshots & fail-closed startup restore:
//   - On startup, if `<state>/server` has no local state files and `[server.backup]` is declared, fetches
//     `splice-state/latest.json` and restores every allowlisted state file atomically. If the backup store cannot be
//     checked or the snapshot is corrupt, throws so the server refuses to start empty over an existing snapshot.
//   - While running, periodically snapshots all present state files to `splice-state/YYYY/MM/DD/HH.json` and
//     `splice-state/latest.json`, and supports on-demand snapshots via `POST /api/server/backup`.
package splice.server.backup

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.topology.ServerBackup
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.server.ServerAnswer
import splice.server.export.BucketKeyNames
import splice.server.export.BucketReach
import splice.upstream.LifecycleScope
import splice.upstream.Ticker
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessTicker
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** How a backup runner runs its background I/O, timestamps, retries, and periodic ticks. */
public data class BackupRun(
    val io: CoroutineDispatcher,
    val clock: Clock,
    val waiter: Waiter,
    val log: LogSink,
    val ticker: Ticker = ProcessTicker(),
)

public class ServerBackupStore public constructor(
    private val config: ServerBackup,
    private val folder: Path,
    private val run: BackupRun,
) {
    private val codec = ServerSnapshotCodec()
    private val scope = LifecycleScope(run.io)
    private val snapshotting = Mutex()

    @Volatile
    private var target: SnapshotTarget? = null

    /** Restores state files from `[server.backup]` when [folder] holds none; fails closed if the backup cannot be read
     *  so a fresh disk never starts empty over an existing snapshot. Must run before stores read [folder]. */
    public fun restoreIfEmpty(env: EnvReader) {
        if (!config.declared) return
        val hasLocal = snapshotStateFiles.any { Files.exists(folder.resolve(it)) }
        if (hasLocal) return
        val store = resolve(env) ?: error("backup target '${config.to}' could not be resolved")
        when (val read = store.get(LATEST_SNAPSHOT_KEY)) {
            SnapshotRead.Missing -> run.log(
                "[server] backup ${LogSafe.str(config.to)} has no snapshot yet; starting fresh\n",
            )
            is SnapshotRead.Failed -> error(
                "refusing to start empty over an unreachable backup (${config.to}): ${read.why}",
            )
            is SnapshotRead.Found -> applyRestore(read.bytes)
        }
    }

    /** Starts scheduled snapshots to `[server.backup]`; no-op when undeclared. */
    internal fun open(env: EnvReader) {
        if (!config.declared) return
        target = resolve(env)
        val every = config.everyMs ?: return
        scope.launch { while (run.ticker.awaitTick(every)) tick() }
    }

    /** Takes one snapshot right now (`POST /api/server/backup` or `POST /server/api/backup`). */
    public suspend fun now(): ServerAnswer {
        val store = target ?: return ServerAnswer.Refused(HttpStatusCode.Conflict, BACKUP_NOT_SET)
        val result = snapshot(store)
        if (result.why != null) {
            return ServerAnswer.Refused(HttpStatusCode.BadGateway, "backup to ${config.to} failed: ${result.why}")
        }
        val body = buildJsonObject {
            put("to", config.to)
            put("key", result.hourKey)
            put("files", result.fileCount)
            put("taken_at", result.takenAt)
        }
        return ServerAnswer.Done(HttpStatusCode.OK, body)
    }

    /** Stops the periodic snapshot loop, letting any snapshot in progress finish. */
    internal suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
    }

    private fun resolve(env: EnvReader): SnapshotTarget? {
        val names = BucketKeyNames(config.accessKeyEnv.orEmpty(), config.secretKeyEnv.orEmpty())
        return SnapshotTargets(run.io).of(config, BucketReach(names, env, run.clock, run.waiter))
    }

    private fun applyRestore(bytes: ByteArray) {
        val parsed = codec.decode(bytes)
        Files.createDirectories(folder)
        parsed.files.forEach { (name, content) ->
            SecureFile.writeAtomic0600(folder.resolve(name), content)
        }
        run.log(
            "[server] restored ${parsed.files.size} state file(s) from backup ${LogSafe.str(config.to)} " +
                "(taken ${LogSafe.str(parsed.takenAt)})\n",
        )
    }

    private suspend fun tick() {
        val store = target ?: return
        val why = Cancellables.runCatchingCancellable { snapshot(store).why }
            .getOrElse(SafeFailureText::render)
        why?.let { run.log("[server] state backup to ${LogSafe.str(config.to)} failed: ${LogSafe.str(it)}\n") }
    }

    private suspend fun snapshot(store: SnapshotTarget): SnapshotDone = snapshotting.withLock {
        val now = run.clock.instant()
        val takenAt = now.toString()
        val hourKey = BACKUP_HOUR_FILE.format(now)
        val files = withContext(run.io) { codec.readLocalFiles(folder) }
        val payload = codec.encode(takenAt, files)
        val hourErr = store.put(hourKey, payload)
        if (hourErr != null) return@withLock SnapshotDone(hourKey, files.size, takenAt, hourErr)
        val latestErr = store.put(LATEST_SNAPSHOT_KEY, payload)
        SnapshotDone(hourKey, files.size, takenAt, latestErr)
    }
}

private data class SnapshotDone(val hourKey: String, val fileCount: Int, val takenAt: String, val why: String? = null)

private val BACKUP_HOUR_FILE =
    DateTimeFormatter.ofPattern("'splice-state/'yyyy/MM/dd/HH'.json'").withZone(ZoneOffset.UTC)

private const val BACKUP_NOT_SET =
    "this server backs up nowhere: splice.toml's [server] has no [server.backup] with a `to`"
