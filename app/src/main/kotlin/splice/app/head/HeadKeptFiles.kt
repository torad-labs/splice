// NEW: V4-260 — the files a head keeps go when their promise ends, not when the head next writes.
// Marlin's disk-writes audit (99244e63a) found three stores holding a head's private content past it:
// an expired compaction answer waited for that head's next save, code-mode state stayed after code
// mode was turned off or the head was removed, and trace days stayed after trace was turned off.
// Heads are assembled only at the daemon's start, so that start is each head's start ([atStart],
// from ManagedHeadFactory) and the first moment a head removed from splice.toml is known to be gone
// ([ofRemovedHeads], from Daemon.start, before any head). The daemon lock makes this daemon the only
// writer of its state dir, so a key the topology does not name belongs to no running head.
package splice.app.head

import splice.core.config.CODE_MODE_STATE_SUFFIX
import splice.core.config.StatePaths
import splice.core.storage.ActivityDays
import splice.core.storage.DayFileStores
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.head.compaction.FileCompactionRecordings
import java.nio.file.Files
import java.nio.file.Path

internal class HeadKeptFiles(private val statePaths: StatePaths, private val log: LogSink) {

    /** At [key]'s start: its expired compaction answers go, its code-mode state goes unless [codeMode]
     *  (the provider's codeModeEnabled, which the Codex arm builds its bridge on), and its trace days
     *  go unless [trace]. */
    fun atStart(key: String, codeMode: Boolean, trace: Boolean) {
        FileCompactionRecordings(statePaths.compactionRecordingsDir(key), log).sweep()
        if (!codeMode) dropCodeMode(key, "code mode is off")
        if (!trace) dropTrace(key, "trace is off")
    }

    /** At the daemon's start: every kept file of a head that is not in [heads]. */
    fun ofRemovedHeads(heads: Set<String>) {
        entries(statePaths.compactionsDir).filter { it.fileName.toString() !in heads }.forEach { dir ->
            FileCompactionRecordings(dir, log).purge()
        }
        entries(statePaths.stateDir)
            .map { it.fileName.toString() }
            .filter { it.endsWith(CODE_MODE_STATE_SUFFIX) }
            .map { it.removeSuffix(CODE_MODE_STATE_SUFFIX) }
            .filter { it !in heads }
            .forEach { key -> dropCodeMode(key, "the head is no longer in splice.toml") }
        DayFileStores(statePaths.traceDir).prefixes().filter { it !in heads }.forEach { key ->
            dropTrace(key, "the head is no longer in splice.toml")
        }
    }

    private fun dropCodeMode(key: String, why: String) {
        val file = statePaths.stateDir.resolve("$key$CODE_MODE_STATE_SUFFIX")
        Cancellables.runCatchingCancellable { Files.deleteIfExists(file) }.fold(
            onSuccess = { deleted -> if (deleted) log("[$key] code-mode state deleted: $why\n") },
            onFailure = { failure ->
                log("[$key] code-mode state could not be deleted (${SafeFailureText.render(failure)}): $why\n")
            },
        )
    }

    private fun dropTrace(key: String, why: String) {
        // Retention is not read by purge, which deletes every day of the head whatever its age.
        val days = ActivityDays(statePaths.traceDir, key, retentionDays = 1, ownerOnly = true).purge()
        if (days.isNotEmpty()) log("[$key] ${days.size} trace day file(s) deleted: $why\n")
    }

    private fun entries(dir: Path): List<Path> =
        // ast-grep-ignore: kt-no-silent-result-collapse -- no directory means nothing was kept there; an unreadable one is listed again at the next start
        Cancellables.runCatchingCancellable { Files.newDirectoryStream(dir).use { it.toList() } }
            .getOrDefault(emptyList())
}
