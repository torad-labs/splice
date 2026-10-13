// NEW: Oct 13, 2026 — split from HeadRemoval.kt along its seam: removing a command's wrapper link from the install's
// bin dir is a file-system concern of its own, which the removal asks for once it knows no other head shares the
// command.
package splice.app.head

import splice.core.config.InstallPaths
import splice.core.util.Cancellables
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

/** Where the wrapper links live; null reads the install's own bin dir. */
internal fun interface WrapperBinSource {
    operator fun invoke(): Path?
}

/** A command's wrapper link in the bin dir, removed only when it is splice's own link there. */
internal class WrapperLinks(private val wrapperBin: WrapperBinSource, private val log: LogSink) {
    /** A link that is not splice's own, or that will not go, stays; the second is said in the log. */
    fun unlink(command: String) {
        val bin = wrapperBin() ?: InstallPaths().binDir
        val link = bin.resolve(command)
        if (!isOwnLink(bin, link, command)) return
        if (Cancellables.runCatchingCancellable { Files.delete(link) }.isFailure) {
            log("[models] the $command wrapper link at $link could not be removed; it stays")
        }
    }

    private fun isOwnLink(bin: Path, link: Path, command: String): Boolean =
        command.isNotEmpty() && link.normalize().parent == bin.normalize() && link.isSymbolicLink()
}
