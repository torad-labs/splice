// NEW (Oct 15, 2026): the splice manager's folder. Marcos: "Make sure this folder is installed with the installer".
// The folder is `manager/` inside splice's global settings folder (the folder that holds splice.toml), where the
// console's composer runs its one resident Claude Code session. It is a subfolder because the settings folder holds the
// secrets (keys.toml, auth/, key-ledger.json): a session whose working folder held them read them to the person's
// chosen head on a plain `grep -rn x .` or `cat *.toml` (seen on a private instance, Oct 15), while one a level down
// holds nothing secret, and a command that reaches outside it asks the person first. Its files ship inside the jar under resources/manager-home, named by that folder's
// manifest.txt because a jar cannot be listed at runtime. `splice init`, which install.sh runs on every install, and
// every daemon start call [ManagerHome.ensure]: auto-update replaces the jar and never reruns init. The shapes are
// HubUnits' (each file splice writes is recorded by SHA-256 in splice's own state) and UpgradeWrapper's (a shipped file
// is refreshed, a local edit is kept): a file absent on disk is written; a file still holding the bytes splice recorded
// is refreshed when this release's copy differs; a file the person edited, or one splice never wrote, is left as it is.
package splice.app.manager

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

/** The manager's folder beside [configFile], and the files splice ships into it. Splice's record of what it wrote there
 *  lives in [stateDir] under manager-home, owner-only. What it keeps as the person left it, and what it cannot write, is
 *  said through [log], the daemon's log unless the caller names another. */
internal class ManagerHome(
    private val configFile: Path,
    private val stateDir: Path,
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val record: Path = stateDir.resolve("manager-home").resolve("written.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** The manager's folder: `manager/` beside splice.toml. Reads nothing and writes nothing. */
    fun folder(): Path = configFile.toAbsolutePath().normalize().parent.resolve(MANAGER_DIR)

    /** Creates the folder when it is absent, then writes or refreshes every shipped file, and returns the folder. A file
     *  that cannot be written is said and skipped, so only a folder that cannot be created throws. A new folder gets the
     *  mode the daemon gives its config folder (the umask's, as TopologyLoader's starter write does); an existing one
     *  keeps its own. */
    fun ensure(): Path {
        val folder = folder()
        Files.createDirectories(folder)
        val before = recorded()
        val after = before.toMutableMap()
        shipped().forEach { name ->
            val bytes = resource(name)?.let { render(name, folder, it) }
            if (bytes == null) {
                log("[manager] the manifest names $name, which this release does not ship; skipped\n")
            } else {
                place(folder, name, bytes, before[name])?.let { after[name] = it }
            }
        }
        if (after != before) saveRecord(after)
        return folder
    }

    /** [ensure] where the manager's folder must never stop the caller (daemon boot, `splice init`): a failure is said
     *  through [log] and answers null. */
    fun ensureBestEffort(): Path? {
        val attempt = Cancellables.runCatchingBestEffort { ensure() }
        attempt.exceptionOrNull()?.let { failure ->
            log("[manager] the manager's folder ${folder()} was not set up: ${SafeFailureText.render(failure)}\n")
        }
        return attempt.getOrNull()
    }

    /** Writes [name] into [folder] when nothing is there, or refreshes it while it still holds the bytes splice recorded
     *  ([recorded]). Answers the SHA-256 to record for it, or null when the record keeps what it had. */
    private fun place(folder: Path, name: String, bytes: ByteArray, recorded: String?): String? {
        val target = folder.resolve(name).normalize()
        if (!target.startsWith(folder) || target == folder) {
            log("[manager] the manifest names $name, which is outside the manager's folder; skipped\n")
            return null
        }
        val shipped = sha256(bytes)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return shipped.takeIf { write(target, name, bytes) }
        val onDisk = onDisk(target)
        return when {
            // The person's copy already holds this release's bytes: nothing to write, and it is splice's from now on.
            onDisk == shipped -> shipped.takeIf { it != recorded }
            onDisk != null && onDisk == recorded -> shipped.takeIf { write(target, name, bytes) }
            else -> {
                val why = if (recorded == null) "splice did not write it" else "it was edited since splice wrote it"
                log("[manager] kept $target as it is, because $why; this release's copy is not installed over it\n")
                null
            }
        }
    }

    /** The SHA-256 of the regular file at [target]; null for a link, a directory or a file that cannot be read, which
     *  splice never counts as its own. */
    private fun onDisk(target: Path): String? {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null
        return Cancellables.runCatchingBestEffort { sha256(Files.readAllBytes(target)) }.getOrNull()
    }

    /** [bytes] at [target] whole or not at all, said through [log] when it fails. The settings fence names where the
     *  secrets are, so it is written owner-only through SecureFile; the rest are readable by all. Folders on the way are
     *  created at the umask's mode, and an existing folder's mode is never changed. */
    private fun write(target: Path, name: String, bytes: ByteArray): Boolean {
        val attempt = Cancellables.runCatchingBestEffort {
            Files.createDirectories(target.parent)
            if (name == MANAGER_SETTINGS_RELATIVE_PATH) {
                SecureFile.writeAtomic0600(target, bytes.toString(Charsets.UTF_8))
            } else {
                writeShared(target, bytes)
            }
        }
        attempt.exceptionOrNull()?.let { failure ->
            log("[manager] could not write $target: ${SafeFailureText.render(failure)}\n")
        }
        return attempt.isSuccess
    }

    /** Staged beside [target], on the disk, given mode 0644, then moved over it, so a reader or a power cut sees the old
     *  file or the new one, never a torn one (a torn file would no longer match the record, and be kept forever). */
    private fun writeShared(target: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(target.parent, ".manager", ".tmp")
        Cancellables.withCleanup({ Files.deleteIfExists(temp) }) {
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    val _ = channel.write(buffer)
                }
                channel.force(true)
            }
            Cancellables.discard(
                Cancellables.runCatchingBestEffort {
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString(SHARED_MODE))
                },
                "a filesystem with no POSIX modes keeps the file at its own; the write still lands",
            )
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** The manifest's paths, one per line, without blank lines and `#` comments. */
    private fun shipped(): List<String> {
        val manifest = resource(MANIFEST)?.toString(Charsets.UTF_8)
        if (manifest == null) {
            log(
                "[manager] this release ships no $MANAGER_HOME_RESOURCE_ROOT/$MANIFEST; " +
                    "the manager's folder gets no files\n",
            )
            return emptyList()
        }
        return manifest.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun resource(name: String): ByteArray? =
        javaClass.getResourceAsStream("$MANAGER_HOME_RESOURCE_ROOT/$name")?.use { it.readBytes() }

    /** The settings fence with this machine's folders in it: the settings folder (the manager's parent) and a `[daemon]
     *  state_dir` or a SPLICE_CONFIG elsewhere are denied by their absolute paths (`//<path>`), beside the shipped defaults. Every caller resolves the same two folders,
     *  so the bytes, and the SHA-256 recorded for them, are the same on every run until a folder moves; then a fence
     *  nobody edited is refreshed to the new one. The other files ship as they are. */
    private fun render(name: String, folder: Path, bytes: ByteArray): ByteArray {
        if (name != MANAGER_SETTINGS_RELATIVE_PATH) return bytes
        return bytes.toString(Charsets.UTF_8)
            .replace(CONFIG_DIR_TOKEN, jsonText(folder.parent))
            .replace(STATE_DIR_TOKEN, jsonText(stateDir.toAbsolutePath().normalize()))
            .toByteArray(Charsets.UTF_8)
    }

    /** [path] as it sits inside a JSON string, escaped and without the quotes. */
    private fun jsonText(path: Path): String = JsonPrimitive(path.toString()).toString().removeSurrounding("\"")

    /** Shipped path to the SHA-256 splice recorded on writing it; empty when nothing is recorded or the record cannot be
     *  read, so every file there then counts as the person's and is kept. */
    private fun recorded(): Map<String, String> = Cancellables.runCatchingBestEffort {
        if (!Files.exists(record)) return@runCatchingBestEffort emptyMap()
        json.decodeFromString<ManagerHomeRecord>(Files.readString(record)).files
    }.getOrNull().orEmpty()

    private fun saveRecord(files: Map<String, String>) {
        val attempt = Cancellables.runCatchingBestEffort {
            SecureFile.writeAtomic0600(record, json.encodeToString(ManagerHomeRecord(files.toSortedMap())) + "\n")
        }
        attempt.exceptionOrNull()?.let { failure ->
            log("[manager] could not save $record: ${SafeFailureText.render(failure)}\n")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/** `<state>/manager-home/written.json`: each file splice wrote into the manager's folder, by its path there, to the
 *  SHA-256 of the bytes it wrote. */
@Serializable
internal data class ManagerHomeRecord(val files: Map<String, String> = emptyMap())

private const val MANAGER_HOME_RESOURCE_ROOT = "/manager-home"
private const val MANIFEST = "manifest.txt"
private const val MANAGER_SETTINGS_RELATIVE_PATH = "${UserHome.CLAUDE_DIR}/settings.json"
private const val SHARED_MODE = "rw-r--r--"
private const val MANAGER_DIR = "manager"

// The settings template's two folders, each written after one slash, so `/@STATE_DIR@/**` renders `//home/.../**`: an
// absolute path in Claude Code's permission rules, where a single leading slash would anchor at the settings folder.
private const val CONFIG_DIR_TOKEN = "@CONFIG_DIR@"
private const val STATE_DIR_TOKEN = "@STATE_DIR@"
