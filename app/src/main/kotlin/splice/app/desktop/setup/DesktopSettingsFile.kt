// NEW: desktop-door (docs/specs/desktop-door.md, decisions 8 and 9). `~/.claude/settings.json`, which the Code tab's
// Claude Code and every terminal session read. splice writes nothing there for Desktop; it only takes out what an older
// splice's `splice desktop on` wrote, which held the desktop key and so left the file owner-only. A file linked from
// elsewhere (a dotfiles repo) is written where the link points, so the link stays.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.app.desktop.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.client.desktop.DESKTOP_SETTINGS_FILE
import splice.client.desktop.DesktopSettingsRead
import splice.client.desktop.SettingsRead
import splice.core.config.UserHome
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal class DesktopSettingsFile(
    val path: Path = UserHome.claudeDir().resolve(DESKTOP_SETTINGS_FILE),
    /** Where the process's own umask is read on Linux; elsewhere there is none to read. */
    private val status: Path = Path.of(PROC_STATUS),
) {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun read(): SettingsRead = DesktopSettingsRead(path).read()

    /** The file, read as [was], replaced by [with], an older splice's door taken out of it; taken away when [with] is
     *  null, since nothing is left of a file that was not there before that splice wrote it. Claude Code writes this
     *  file too and holds no lock splice could wait on, so the file is read once more just before: when it no longer
     *  reads as [was], it is left as Claude Code wrote it and the answer is false, for the caller to look again. */
    fun replace(was: JsonObject, with: JsonObject?): Boolean {
        val target = target()
        if ((read() as? SettingsRead.Held)?.file != was) return false
        if (with == null) Files.deleteIfExists(target) else write(target, with)
        return true
    }

    /** [file] written whole and atomically as Claude Code writes it: two spaces, a newline at the end. The file keeps
     *  its mode, and one an older splice's door left readable by its owner alone gets the mode Claude Code gives a
     *  file it creates, where the umask can be read; it is under its name at no other mode, not for an instant. On a
     *  filesystem without POSIX modes the write is all there is. */
    private fun write(target: Path, file: JsonObject) {
        val text = json.encodeToString(JsonObject.serializer(), file) + "\n"
        if (!Files.getFileStore(target).supportsFileAttributeView(POSIX_VIEW)) {
            SecureFile.writeAtomic0600(target, text)
            return
        }
        val before = Files.getPosixFilePermissions(target)
        SecureFile.writeAtomic(target, text, if (before.none { it in OTHERS_MODES }) created() ?: before else before)
    }

    /** Where a write lands: through a link to the file it names, so the link is kept. */
    private fun target(): Path = if (Files.isSymbolicLink(path)) path.toRealPath() else path

    /** This process's umask, or null where it can't be read. */
    private fun umask(): Int? {
        if (!Files.isReadable(status)) return null
        return Files.readAllLines(status).firstOrNull { it.startsWith(UMASK_FIELD) }
            ?.removePrefix(UMASK_FIELD)?.trim()?.toIntOrNull(OCTAL)
    }

    /** The mode a file is created with here: read and write for all, less this process's umask; null where the umask
     *  can't be read. */
    private fun created(): Set<PosixFilePermission>? {
        val umask = umask() ?: return null
        val mode = CREATED_MODE and umask.inv()
        val symbolic = PERMISSION_LETTERS.indices.joinToString("") { bit ->
            val on = mode and (1 shl (PERMISSION_LETTERS.length - 1 - bit)) != 0
            if (on) PERMISSION_LETTERS[bit].toString() else "-"
        }
        return PosixFilePermissions.fromString(symbolic)
    }
}

// What the group and others may do: a file that grants them none of it is readable by its owner alone.
private val OTHERS_MODES: Set<PosixFilePermission> = PosixFilePermission.entries.toSet() -
    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)

// The attribute view a filesystem with POSIX modes offers.
private const val POSIX_VIEW = "posix"

// The status file of this process on Linux: its `Umask:` line holds the umask, its `NoNewPrivs:` line the bit
// `splice desktop launch` escapes (PrivilegeEscape).
internal const val PROC_STATUS: String = "/proc/self/status"
private const val UMASK_FIELD = "Umask:"

// why: the kernel writes the umask in octal (`Umask:\t0002`), so it is read in base 8.
private const val OCTAL = 8

// why: rw-rw-rw-, the mode Node gives a file it creates before the umask takes its share, as Claude Code creates this
// file.
private const val CREATED_MODE = 0b110_110_110

// The letters of a mode, owner then group then others, one per bit from the highest.
private const val PERMISSION_LETTERS = "rwxrwxrwx"
