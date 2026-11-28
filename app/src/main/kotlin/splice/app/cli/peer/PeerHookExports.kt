package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The only session exports a successful attach supplies: a name and the path to its key, never the key. */
internal class PeerHookExports(private val reply: JsonObject) {
    fun lines(): List<String> {
        val name = JsonScalars.str(reply, "name") ?: return emptyList()
        val path = JsonScalars.str(reply, "keyFile") ?: return emptyList()
        return listOf("export $PEER_NAME_ENV=${word(name)}", "export $PEER_KEY_FILE_ENV=${word(path)}")
    }

    /** The harness owns this existing file; append shell assignments without creating or replacing it. */
    fun appendTo(file: String?, err: TerminalOutput) {
        if (file.isNullOrBlank()) return
        Cancellables.runCatchingCancellable {
            Files.newBufferedWriter(Path.of(file), StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use { writer ->
                // A previous hook may not have terminated its last assignment.
                writer.newLine()
                lines().forEach { writer.appendLine(it) }
            }
        }.onFailure { err.line("splice peer: session exports were not written: ${SafeFailureText.render(it)}") }
    }

    private fun word(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
