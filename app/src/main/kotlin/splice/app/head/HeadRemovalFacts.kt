// NEW: Oct 12, 2026 — what a removal reads about a command: who is on it now, and how much history it leaves in place.
package splice.app.head

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.ManagedHead
import splice.app.control.SessionHeadAdapter
import splice.app.sources.PerfRowsFileSource
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** The session registry, read when asked: it is wired after the control plane exists. */
internal fun interface RegisteredSessions {
    operator fun invoke(): SessionSource?
}

internal class HeadRemovalFacts(
    private val statePaths: StatePaths,
    heads: Map<String, ManagedHead>,
    private val sessions: RegisteredSessions,
) {
    private val roots = SessionHeadAdapter.adapt(heads)

    /** The folder the command's sessions wrote under, which a retired record keeps readable. */
    fun transcriptRoot(key: String): Path? = roots[key]?.transcriptRoot

    fun liveOn(key: String): List<SessionRecord> = sessions()?.read().orEmpty()
        .filter { it.head == key && it.availability == SessionAvailability.LIVE }

    fun sessionList(records: List<SessionRecord>) = buildJsonArray {
        records.forEach { record ->
            add(
                buildJsonObject {
                    put("id", record.sessionId)
                    put("name", record.name)
                },
            )
        }
    }

    fun requestsOf(key: String): Int = Cancellables.runCatchingCancellable {
        PerfRowsFileSource(statePaths.perfStatsFile(key), statePaths.perfArchiveDir).window(0).rows.size
    }.fold(onSuccess = { it }, onFailure = { 0 })

    /** The transcripts the command's sessions wrote, which stay readable from Sessions. */
    fun sessionsOf(key: String): Int {
        val root = transcriptRoot(key) ?: return 0
        if (!Files.isDirectory(root)) return 0
        return Cancellables.runCatchingCancellable {
            Files.walk(root).use { walk -> walk.filter { it.extension == "jsonl" }.count().toInt() }
        }.fold(onSuccess = { it }, onFailure = { 0 })
    }
}
