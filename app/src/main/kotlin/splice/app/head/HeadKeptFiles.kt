// NEW: V4-260 — the files a head keeps are deleted at the daemon's start once their use is over, not
// only when the head next writes. Marlin's disk-writes audit (99244e63a) found three stores holding a
// head's private content past it: an expired compaction answer waited for that head's next save,
// code-mode state stayed after code mode was turned off or the head was removed, and trace days
// stayed after trace was turned off. What happens now (V4-286 says it as it is): an expired
// compaction answer goes at its head's start or at that head's next save, whichever comes first;
// code-mode state goes at the first start with code mode off or the head gone from splice.toml; trace
// days go at the first start with trace off or the head gone. While the daemon runs, nothing here
// deletes anything. Heads are assembled only at the daemon's start, so that start is each head's start
// ([atStart], from ManagedHeadFactory) and the first moment a head removed from splice.toml is known
// to be gone ([ofRemovedHeads], from Daemon.start, before any head). The daemon lock makes this daemon
// the only writer of its state dir, so a key the topology does not name belongs to no running head.
//
// EVERY OUTCOME IS LOGGED AS IT IS (V4-286). A file that could not be deleted, or a directory that
// could not be listed, is a log line naming it and why, never a "deleted" line or silence, and never an
// exception out of the daemon's start. A trace value that is not a bool keeps the head's trace days:
// ConfigService has already named that value as refused, and a typo is not "trace is off".
package splice.app.head

import splice.core.config.BoolKnobWords
import splice.core.config.CODE_MODE_STATE_SUFFIX
import splice.core.config.StatePaths
import splice.core.storage.DayFileStores
import splice.core.storage.DayFiles
import splice.core.storage.DayPurge
import splice.core.storage.DirectoryEntries
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.head.compaction.FileCompactionRecordings
import java.nio.file.Files
import java.nio.file.Path

internal class HeadKeptFiles(private val statePaths: StatePaths, private val log: LogSink) {

    /** At [key]'s start: its expired compaction answers go, its code-mode state goes unless [codeMode]
     *  (the provider's codeModeEnabled, which the Codex arm builds its bridge on), and its trace days
     *  go unless [trace], or unless [traceWritten], the head's trace value in splice.toml, is not a
     *  bool. */
    fun atStart(key: String, codeMode: Boolean, trace: Boolean, traceWritten: String?) {
        FileCompactionRecordings(statePaths.compactionRecordingsDir(key), log).sweep()
        if (!codeMode) dropCodeMode(key, "code mode is off")
        val unread = traceWritten != null && BoolKnobWords.of(traceWritten) == null
        when {
            trace -> Unit
            unread -> log("[$key] trace days kept: its trace value '$traceWritten' is not true or false\n")
            else -> dropTrace(key, "trace is off")
        }
    }

    /** At the daemon's start: every kept file of a head that is not in [heads]. */
    fun ofRemovedHeads(heads: Set<String>) {
        listed(statePaths.compactionsDir) { DirectoryEntries.of(it) }
            .filter { it.fileName.toString() !in heads }
            .forEach { dir -> FileCompactionRecordings(dir, log).purge() }
        listed(statePaths.stateDir) { DirectoryEntries.of(it) }
            .map { it.fileName.toString() }
            .filter { it.endsWith(CODE_MODE_STATE_SUFFIX) }
            .map { it.removeSuffix(CODE_MODE_STATE_SUFFIX) }
            .filter { it !in heads }
            .forEach { key -> dropCodeMode(key, "the head is no longer in splice.toml") }
        listed(statePaths.traceDir) { DayFileStores(it).prefixes() }.filter { it !in heads }.forEach { key ->
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
        when (val purge = DayFiles(statePaths.traceDir, key).purge()) {
            is DayPurge.Unlisted ->
                log("[$key] trace days could not be listed (${SafeFailureText.render(purge.failure)}): $why\n")
            is DayPurge.Listed -> {
                if (purge.deleted.isNotEmpty()) log("[$key] ${purge.deleted.size} trace day file(s) deleted: $why\n")
                purge.failed.forEach { (file, failure) ->
                    log("[$key] trace file $file could not be deleted (${SafeFailureText.render(failure)}): $why\n")
                }
            }
        }
    }

    /** What [list] finds in [dir]: none when [dir] does not exist, and none with a log line naming
     *  [dir] and why when it cannot be listed, so a removed head's files there wait for the next start. */
    private inline fun <T> listed(dir: Path, list: (Path) -> Collection<T>): Collection<T> =
        Cancellables.runCatchingCancellable { list(dir) }.getOrElse { failure ->
            log(
                "[kept-files] $dir could not be listed (${SafeFailureText.render(failure)}): " +
                    "a removed head's files there stay until the next start\n",
            )
            emptyList()
        }
}
