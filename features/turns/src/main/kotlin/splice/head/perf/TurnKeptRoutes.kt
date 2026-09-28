// NEW: V4-381 — the guarded console's source-derived inventory of retained turn statistics.
package splice.head.perf

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.config.StatePaths
import splice.core.perf.PerfArchiveName
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val LIVE_SUFFIX = "-perf.jsonl"
private const val PERF_ROLLED_SUFFIX = "-perf.jsonl.1"
private const val ARCHIVED_INFIX = "-perf.jsonl-"
private const val TOTALS_SUFFIX = "-session-totals.json"
private const val DELETED_MARKER = "turns.deleted"

/** This failure's text is fixed by us, so it cannot quote a file name or its bytes. */
private class InvalidTurnFile : IOException("not a regular turn file")

private class TurnTally {
    val days = HashSet<LocalDate>()
    var rows = 0L
    var unknown = 0L

    fun add(day: LocalDate?) {
        rows += 1
        if (day == null) unknown += 1 else days += day
    }
}

/** A physical count, not a list of configured heads: removed heads can still have private turns. */
public class TurnKeptRoutes(private val paths: StatePaths?) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Inventory live, rolled and archived perf rows; session totals count only toward deletion state. */
    public fun kept(): JsonReply {
        val source = paths ?: return refuse(HttpStatusCode.ServiceUnavailable, "turn statistics paths are not wired")
        return Cancellables.runCatchingCancellable { inventory(source) }.fold(
            onSuccess = { JsonReply(HttpStatusCode.OK, it) },
            onFailure = { failure ->
                val detail = when (failure) {
                    is InvalidTurnFile -> "not a regular turn file"
                    else -> SafeFailureText.render(failure)
                }
                refuse(
                    HttpStatusCode.InternalServerError,
                    "cannot inspect turn statistics: $detail",
                )
            },
        )
    }

    private fun inventory(source: StatePaths): String {
        val live = entries(source.stateDir)
        val archive = entries(source.perfArchiveDir)
        val files = live.filter { isLive(it.fileName.toString()) } +
            archive.filter { isArchived(it.fileName.toString()) }
        val totalsPresent = live.any { it.fileName.toString().endsWith(TOTALS_SUFFIX) }
        val tally = tally(files)
        val deleted = files.isEmpty() && !totalsPresent &&
            Files.isRegularFile(source.stateDir.resolve(DELETED_MARKER), LinkOption.NOFOLLOW_LINKS)
        return buildJsonObject {
            put("store", "turns")
            put("state", if (deleted) "deleted" else "on")
            if (deleted) {
                put("reason", "turns deleted")
            } else if (tally.unknown > 0) {
                put("reason", "${tally.unknown} turn rows have no usable timestamp")
            }
            put("days", tally.days.size)
            put("rows", tally.rows)
            put("oldest", tally.days.minOrNull()?.toString())
            // Neither a live generation nor an archive is swept on a clock (PerfStats.sweepArchive).
            put("ages_out", null as String?)
        }.toString()
    }

    private fun tally(files: List<Path>): TurnTally = TurnTally().also { count ->
        files.forEach { file ->
            if (!Files.isSymbolicLink(file)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw InvalidTurnFile()
                }
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), decoder)
                    .buffered().useLines { lines -> lines.forEach { count.add(rowDay(it)) } }
            }
        }
    }

    private fun entries(dir: Path): List<Path> {
        if (Files.isSymbolicLink(dir)) throw IOException("turn statistics directory is a symlink: $dir")
        return try {
            Files.newDirectoryStream(dir).use { it.toList() }
        } catch (_: NoSuchFileException) {
            emptyList()
        }
    }

    private fun isLive(name: String): Boolean =
        (name.endsWith(LIVE_SUFFIX) && name.length > LIVE_SUFFIX.length) ||
            (name.endsWith(PERF_ROLLED_SUFFIX) && name.length > PERF_ROLLED_SUFFIX.length)

    private fun isArchived(name: String): Boolean {
        val split = name.lastIndexOf(ARCHIVED_INFIX)
        if (split <= 0) return false
        val liveName = name.substring(0, split) + LIVE_SUFFIX
        return PerfArchiveName(liveName).rotatedAt(name) != null
    }

    private fun rowDay(line: String): LocalDate? = Cancellables.runCatchingCancellable {
        val stamp = (json.parseToJsonElement(line).jsonObject["ts"] as? JsonPrimitive)?.longOrNull
        stamp?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    }.fold(onSuccess = { it }, onFailure = { null })

    private fun refuse(status: HttpStatusCode, why: String): JsonReply =
        JsonReply(status, buildJsonObject { put("error", why) }.toString())
}
