package splice.core.compaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapCategory
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

// why: Estimate 256 bytes for fixed file-result and evidence overhead; ids and paths are counted separately.
private const val PROJECT_FILE_BYTES = 256L

// why: Estimate 128 bytes for fixed path overhead; path characters are counted separately.
private const val PROJECT_PATH_BYTES = 128L

internal data class ProjectRegistration(val sessionId: String?, val cwd: Path?)
private data class TranscriptProject(val sessionId: String, val cwd: Path?)

/** Bounded directory inventories and positive/negative file results. Cache misses still enumerate all candidates. */
internal class ProjectLookupFiles(private val clock: ElapsedClock, private val heap: HeapReservations? = null) {
    private val json = Json
    private val directories = ProjectEvidenceCache<List<Path>>(heap)
    private val registrations = ProjectEvidenceCache<ProjectRegistration>(heap)
    private val transcripts = ProjectEvidenceCache<TranscriptProject>(heap)

    fun entries(dir: Path): List<Path> {
        val started = clock()
        val evidence = ProjectEvidenceReader.read(dir) ?: return emptyList()
        if (!evidence.directory) return emptyList()
        return directories.lookup(dir, evidence)?.value ?: inventory(dir, evidence, started)
    }

    private fun inventory(dir: Path, evidence: ProjectEvidence, started: Long): List<Path> {
        val entries = list(dir) ?: return emptyList()
        if (ProjectEvidenceReader.read(dir) == evidence) {
            val bytes = pathBytes(dir) + entries.sumOf(::pathBytes)
            directories.remember(dir, evidence, entries, bytes, started)
        } else if (heap != null && entries.isNotEmpty()) {
            observe(entries, pathBytes(dir) + entries.sumOf(::pathBytes))
        }
        return entries
    }

    private fun list(dir: Path): List<Path>? = try {
        Files.list(dir).use { it.toList() }
    } catch (_: IOException) {
        null
    } catch (_: UncheckedIOException) {
        null
    }

    fun registration(file: Path): ProjectRegistration? {
        val started = clock()
        val evidence = ProjectEvidenceReader.read(file) ?: return null
        if (!evidence.regular) return null
        val known = registrations.lookup(file, evidence)
        return if (known != null) known.value else registrationAt(file, evidence, started)
    }

    private fun registrationAt(file: Path, evidence: ProjectEvidence, started: Long): ProjectRegistration? {
        val text = PathProbe.text(file) ?: return null // Transient read failures are not remembered.
        val record = parse(text)?.let { ProjectRegistration(JsonScalars.str(it, "sessionId"), cwd(it)) }
        if (heap != null) record?.cwd?.let { observe(it, pathBytes(it)) }
        if (ProjectEvidenceReader.read(file) != evidence) return null
        val bytes = pathBytes(file) + PROJECT_FILE_BYTES + 2L * (record?.sessionId?.length ?: 0) +
            (record?.cwd?.let(::pathBytes) ?: 0L)
        registrations.remember(file, evidence, record, bytes, started)
        return record
    }

    fun transcript(file: Path, sessionId: String): Path? {
        val started = clock()
        val evidence = ProjectEvidenceReader.read(file)
        val known = transcripts.lookup(file, evidence)?.value?.takeIf { it.sessionId == sessionId }
        return when {
            evidence != null && !evidence.regular -> null
            known != null -> known.cwd
            else -> transcriptAt(file, sessionId, evidence, started)
        }
    }

    private fun transcriptAt(file: Path, sessionId: String, evidence: ProjectEvidence?, started: Long): Path? {
        val record = if (evidence == null) {
            TranscriptProject(sessionId, null)
        } else {
            readTranscript(file, sessionId) ?: return null
        }
        if (heap != null) record.cwd?.let { observe(it, pathBytes(it)) }
        if (ProjectEvidenceReader.read(file) != evidence) return null
        val bytes = pathBytes(file) + PROJECT_FILE_BYTES + 2L * sessionId.length + (record.cwd?.let(::pathBytes) ?: 0L)
        transcripts.remember(file, evidence, record, bytes, started)
        return record.cwd
    }

    private fun readTranscript(file: Path, sessionId: String): TranscriptProject? = try {
        val cwd = Files.newBufferedReader(file).useLines { lines ->
            lines.mapNotNull(::parse).firstNotNullOfOrNull { row ->
                if (JsonScalars.str(row, "sessionId") == sessionId) cwd(row) else null
            }
        }
        TranscriptProject(sessionId, cwd)
    } catch (_: IOException) {
        null
    } catch (_: UncheckedIOException) {
        null
    }

    private fun parse(text: String): JsonObject? = JsonScalars.objectOrNull(json, text)

    private fun cwd(row: JsonObject): Path? = JsonScalars.str(row, "cwd")?.let(PathProbe::spelled)
        ?.normalize()?.takeIf { it.isAbsolute }

    private fun <O : Any> observe(owner: O, bytes: Long) {
        heap?.let {
            HeapOwners.observe(owner, it, bytes, HeapCategory.TRANSCRIPT_CACHE, HeapOwnerState.AWAITING_COLLECTION)
        }
    }

    private fun pathBytes(path: Path): Long = PROJECT_PATH_BYTES + 2L * path.toString().length
}
