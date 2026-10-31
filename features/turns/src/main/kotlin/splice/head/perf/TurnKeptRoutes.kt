// NEW: V4-381 — the guarded console's delete of retained turn statistics. Its inventory (GET /api/kept/turns) is gone:
// no console page, CLI or tool read a key of it (splice-turns, Oct 12).
package splice.head.perf

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.StatePaths
import splice.core.perf.PerfFiles
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonWire
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.http.JsonReply
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TOTALS_SUFFIX = "-session-totals.json"

// why: scanning and deleting years of perf rows may take longer than one head's drain budget;
// the file lane still owns the task if this bounded HTTP wait expires.
private const val TURN_DELETE_WAIT_MS = 120_000L

/** A new successful perf append clears the durable post-delete state. */
internal const val TURN_STATS_DELETED_MARKER = "turns.deleted"

/** This failure's text is fixed by us, so it cannot quote a file name or its bytes. */
internal class InvalidTurnFile : IOException("not a regular turn file")

private data class KeptTurnFiles(val perf: List<Path>, val totals: List<Path>)

/** A physical count, not a list of configured heads: removed heads can still have private turns. */
public class TurnKeptRoutes(
    private val paths: StatePaths?,
    private val liveTotals: Map<String, SessionTotals> = emptyMap(),
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    /** Remove all retained turn lines and derived session totals after earlier file-lane appends.
     *  A later successful turn starts a new live file and clears the durable deletion marker. */
    public fun delete(): JsonReply {
        val source = paths ?: return refuse(HttpStatusCode.ServiceUnavailable, "turn statistics paths are not wired")
        return Cancellables.runCatchingCancellable { deleteAfterWrites(source) }.fold(
            onSuccess = { removed ->
                log("[turn-stats] deleted ${LogSafe.str(removed.toString())} file(s)\n")
                JsonReply(HttpStatusCode.OK, "{}")
            },
            onFailure = { failure ->
                val detail = when (failure) {
                    is InvalidTurnFile -> "not a regular turn file"
                    else -> SafeFailureText.render(failure)
                }
                refuse(HttpStatusCode.InternalServerError, "cannot delete turn statistics: $detail")
            },
        )
    }

    private fun deleteAfterWrites(source: StatePaths): Int {
        val ready = CountDownLatch(1)
        val result = AtomicReference<Result<Int>?>(null)
        val queued = AsyncFileIo.submit {
            try {
                result.set(Cancellables.runCatchingCancellable { remove(source) })
            } finally {
                ready.countDown()
            }
        }
        if (!queued) throw IOException("the file lane refused the turn-statistics deletion")
        if (!ready.await(TURN_DELETE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw IOException("turn-statistics deletion is still running; read inventory before retrying")
        }
        return checkNotNull(result.get()).getOrThrow()
    }

    private fun remove(source: StatePaths): Int {
        val kept = files(source)
        validateTotals(kept.totals)
        val removed = kept.perf.count { Files.deleteIfExists(it) } + deleteTotals(kept.totals)
        val _ = SecureFile.ownerOnlyDirectory(source.stateDir)
        SecureFile.writeAtomic0600(source.stateDir.resolve(TURN_STATS_DELETED_MARKER), "deleted\n")
        return removed
    }

    private fun validateTotals(files: List<Path>) {
        files.forEach { file ->
            if (!Files.isSymbolicLink(file) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw InvalidTurnFile()
            }
        }
    }

    private fun deleteTotals(files: List<Path>): Int {
        val live = liveTotals.values.count { it.deleteKept() }
        val orphaned = files.filterNot { it.fileName.toString().removeSuffix(TOTALS_SUFFIX) in liveTotals }
        return live + orphaned.count { Files.deleteIfExists(it) }
    }

    private fun files(source: StatePaths): KeptTurnFiles {
        val live = entries(source.stateDir)
        val archive = entries(source.perfArchiveDir)
        return KeptTurnFiles(
            perf = live.filter { PerfFiles.isLive(it.fileName.toString()) } +
                archive.filter { PerfFiles.isArchived(it.fileName.toString()) },
            totals = live.filter { it.fileName.toString().endsWith(TOTALS_SUFFIX) },
        )
    }

    private fun entries(dir: Path): List<Path> {
        if (Files.isSymbolicLink(dir)) throw IOException("turn statistics directory is a symlink: $dir")
        return try {
            Files.newDirectoryStream(dir).use { it.toList() }
        } catch (_: NoSuchFileException) {
            emptyList()
        }
    }

    private fun refuse(status: HttpStatusCode, why: String): JsonReply =
        JsonReply(status, JsonWire.string(buildJsonObject { put("error", why) }))
}
