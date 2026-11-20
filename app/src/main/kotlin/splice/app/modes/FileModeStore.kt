// NEW: where a mode keeps what it needs per session (modes/README.md, "The contract"): one file per session under
// splice's state folder, <state>/modes/<mode>/<session>.json, written atomically (temp file, then rename) before the
// call's answer goes back, so a restarted daemon reads it back.
package splice.app.modes

import splice.core.util.SecureFile
import splice.modes.api.ModeStore
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Mode [mode]'s store under [stateDir]. */
internal class FileModeStore(stateDir: Path, mode: String) : ModeStore {
    private val dir = stateDir.resolve(STORE_DIR).resolve(mode)

    override fun read(session: String): String? = try {
        Files.readString(file(session))
    } catch (_: NoSuchFileException) {
        null
    }

    override fun write(session: String, text: String) {
        SecureFile.writeAtomic0600(file(session), text)
    }

    /** The session's file: its id made filename-safe, every character outside letters, digits, `-` and `_` an `_`. */
    private fun file(session: String): Path = dir.resolve(session.replace(UNSAFE, "_").ifEmpty { "_" } + ".json")
}

private const val STORE_DIR = "modes"
private val UNSAFE = Regex("[^A-Za-z0-9_-]")
