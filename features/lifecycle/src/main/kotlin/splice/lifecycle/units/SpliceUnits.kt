// NEW (Oct 25, 2026, splice-lead, on Host Shield's handover): splice lays its own systemd user units. Marcos ruled
// that Host Shield vendors no splice, so the units its slice laid in ~/.config/systemd/user on Oct 23 retire with its
// next install, and without a unit of splice's own the daemon would have nothing to come back under after a restart
// or a reboot. Splice's units live in $XDG_DATA_HOME/systemd/user, the folder systemd keeps for a user's installed
// software, which the user manager searches after ~/.config/systemd/user (systemd.unit(5), "User Unit Search Path").
// A file of the same name in ~/.config/systemd/user overrides splice's and wins until its owner removes it, so
// neither side ever writes over the other. A wants link keeps working when its unit's file moves between the two
// folders, since systemd follows the link by its name: systemd 257 loaded a unit from the data folder through a
// dangling default.target.wants link (Oct 25, 2026).
// Before Oct 25 splice wrote the MCP hub's units into ~/.config/systemd/user itself, create-only, and recorded each
// one's SHA-256 in units-written. A copy that still holds the recorded bytes goes, so splice's laid unit runs.
package splice.lifecycle.units

import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import splice.lifecycle.start.UserManagerBus
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/** The daemon's unit, as splice lays it. */
public const val DAEMON_UNIT_NAME: String = "splice.service"

/** Splice's own systemd user units, laid in [unitDir], the folder systemd keeps for a user's installed software,
 *  beneath [overrideDir], the user's own folder, whose files of the same name win. Splice never writes or deletes a
 *  file in [overrideDir], except a copy its own earlier installs wrote there, recorded in [legacyRecord], which goes
 *  while it still holds the recorded bytes. [command] reaches the user manager. */
public class SpliceUnits(
    private val unitDir: Path,
    private val overrideDir: Path,
    private val legacyRecord: Path,
    private val output: TerminalOutput,
    private val command: List<String> = listOf("systemctl", "--user"),
) {
    /** Lays each of [units], unit name to splice's bytes for it: written where splice's file is missing or holds
     *  other bytes, left where it already holds them, and the user manager reloaded when anything changed. Names the
     *  file that overrides splice's where one does. Nothing is enabled, started, stopped or restarted. */
    public fun lay(units: Map<String, ByteArray>) {
        val laid = units.filter { (name, bytes) -> write(name, bytes) }.keys
        val retired = retireLegacy(units.keys)
        laid.forEach { output.line("splice: laid $it in $unitDir") }
        retired.forEach { output.line("splice: removed the $it splice wrote in $overrideDir, so its laid one runs") }
        units.keys.map(overrideDir::resolve).filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }.forEach {
            output.line("splice: $it overrides splice's ${it.fileName}, so systemd runs it until its owner removes it")
        }
        if (laid.isEmpty() && retired.isEmpty()) return
        if (systemctl(listOf("daemon-reload")) == null) {
            output.line(
                "splice: systemctl --user daemon-reload did not run, so the user manager reads splice's units " +
                    "when it next reloads or starts",
            )
        }
    }

    /** systemd loads [name] from splice's own file in [unitDir], which holds [bytes], with no drop-in on it. */
    internal fun ours(name: String, bytes: ByteArray): Boolean {
        val file = unitDir.resolve(name)
        return held(file)?.contentEquals(bytes) == true &&
            systemctl(listOf("show", "-p", "FragmentPath", "--value", name)) == file.toString() &&
            systemctl(listOf("show", "-p", "DropInPaths", "--value", name)) == ""
    }

    /** The names of the files in splice's own folder. */
    internal fun laid(): List<String> = Cancellables.runCatchingBestEffort {
        if (!Files.isDirectory(unitDir)) return@runCatchingBestEffort emptyList()
        Files.list(unitDir).use { files -> files.map { it.fileName.toString() }.toList() }
    }.getOrNull().orEmpty()

    /** splice's own file for [name] holds [bytes]. */
    internal fun holds(name: String, bytes: ByteArray): Boolean =
        held(unitDir.resolve(name))?.contentEquals(bytes) == true

    /** Removes splice's own file for [name], and says so where it cannot. */
    internal fun remove(name: String) {
        val gone = Cancellables.runCatchingBestEffort { Files.deleteIfExists(unitDir.resolve(name)) }
        gone.exceptionOrNull()?.let { output.line("splice: could not remove $name from $unitDir: ${it.message}") }
        if (gone.isSuccess) output.line("splice: removed $name from $unitDir")
    }

    /** The names of the files in splice's own folder that splice laid: each starts with splice's marker line, so a file
     *  another program put in the same folder is never among them. */
    internal fun marked(): List<String> = laid().filter { name ->
        held(unitDir.resolve(name))?.let { bytes -> bytes.decodeToString().startsWith(SPLICE_UNIT_MARKER) } == true
    }

    /** The user's own file for [name] in [overrideDir], which systemd runs instead of splice's; null where none is. */
    internal fun overriding(name: String): Path? =
        overrideDir.resolve(name).takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }

    /** A unit file, a link or a mask of [name] is in either folder. */
    internal fun present(name: String): Boolean =
        listOf(unitDir, overrideDir).any { Files.exists(it.resolve(name), LinkOption.NOFOLLOW_LINKS) }

    /** The user manager's trimmed answer to [args] when it exits 0 within the bound; null for any other outcome. */
    internal fun systemctl(args: List<String>): String? = Cancellables.runCatchingBestEffort {
        val process = UserManagerBus.supply(ProcessBuilder(command + args))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val done = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done) process.destroyForcibly()
        out.trim().takeIf { done && process.exitValue() == 0 }
    }.getOrNull()

    /** Puts [bytes] in [unitDir] as [name] through a sibling file and a rename, so systemd never reads half a unit.
     *  True when the file changed; false where it already held them, or where the write failed, which it says. */
    private fun write(name: String, bytes: ByteArray): Boolean {
        val file = unitDir.resolve(name)
        if (held(file)?.contentEquals(bytes) == true) return false
        val written = Cancellables.runCatchingBestEffort {
            Files.createDirectories(unitDir)
            val staged = Files.createTempFile(unitDir, ".$name.", ".new")
            try {
                Files.write(staged, bytes)
                Files.setPosixFilePermissions(staged, UNIT_MODE)
                Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(staged)
            }
        }
        written.exceptionOrNull()?.let { output.line("splice: could not lay $name in $unitDir: ${it.message}") }
        return written.isSuccess
    }

    /** Removes the copies of [names] splice's earlier installs wrote into [overrideDir] while they still hold the
     *  bytes [legacyRecord] recorded, and forgets those names; a copy someone changed since is theirs and stays.
     *  Returns the names removed. */
    private fun retireLegacy(names: Set<String>): List<String> {
        val recorded = legacy()
        val due = recorded.keys.filter { it in names }
        if (due.isEmpty()) return emptyList()
        val removed = due.filter { name ->
            val file = overrideDir.resolve(name)
            held(file)?.let(::sha256) == recorded[name] &&
                Cancellables.runCatchingBestEffort { Files.delete(file) }.isSuccess
        }
        val kept = recorded - due.toSet()
        Cancellables.runCatchingBestEffort {
            if (kept.isEmpty()) {
                Files.deleteIfExists(legacyRecord)
            } else {
                SecureFile.writeAtomic0600(legacyRecord, kept.entries.joinToString("") { "${it.value}  ${it.key}\n" })
            }
        }
        return removed
    }

    /** Unit name to the SHA-256 splice recorded on writing it into [overrideDir]; empty when nothing is recorded or
     *  the record is unreadable. */
    private fun legacy(): Map<String, String> = Cancellables.runCatchingBestEffort {
        if (!Files.exists(legacyRecord)) return@runCatchingBestEffort emptyMap()
        Files.readAllLines(legacyRecord).mapNotNull { line ->
            line.split("  ", limit = 2).takeIf { it.size == 2 }?.let { (hash, unit) -> unit to hash }
        }.toMap()
    }.getOrNull().orEmpty()

    /** The bytes of the regular file at [file], not following a link; null where there is none or it is unreadable. */
    private fun held(file: Path): ByteArray? = Cancellables.runCatchingBestEffort {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.readAllBytes(file) else null
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

// why: the bound on one systemctl --user call; a call still running past it is killed and reads as no answer.
private const val TIMEOUT_MS = 15_000L

// why: the first line of every unit splice lays; it tells splice's files from another program's in a shared folder.
private const val SPLICE_UNIT_MARKER = "# splice-owned unit"
private val UNIT_MODE = PosixFilePermissions.fromString("rw-r--r--")
