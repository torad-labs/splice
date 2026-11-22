// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md). One home for the life of the scratch folder every run
// of agy gets: made here or said why not ([AgyFolders.make]), handed out as the folder agy starts in and writes its
// standard error into, taken back when that run ends ([AgyScratch.remove]), which leaves a word behind if it will not
// go. The two who run agy — [AgyLaunch] for Desktop's sessions, [AgyModels] for the roster — had each kept their own
// near-equal copy.
package splice.provider.antigravity

import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The scratch folder one run of agy works in: [path] while there is one, [reason] saying why there is none otherwise. */
internal class AgyScratch(val path: Path?, val reason: String = "") {
    /** Ends this run's share of the folder: it is removed now, said to [log] when it stays behind. */
    fun remove(log: LogSink) {
        val kept = path?.takeUnless { it.toFile().deleteRecursively() }
        if (kept != null) log("[antigravity] $kept was not removed\n")
    }
}

/** How a run of agy gets its scratch folder: made on asking, or said why none came. */
internal class AgyFolders {
    /** The folder made now, for the one run that asked. */
    fun make(): AgyScratch = try {
        AgyScratch(Files.createTempDirectory(SPLICE_AGY_PREFIX))
    } catch (noFolder: IOException) {
        AgyScratch(null, "agy's scratch folder could not be made (${SafeFailureText.render(noFolder)})")
    }
}

/** What names the scratch folders splice makes for agy, among everything else in the temporary folder. */
private const val SPLICE_AGY_PREFIX = "splice-agy-"
