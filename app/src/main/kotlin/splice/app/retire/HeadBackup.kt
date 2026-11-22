// NEW: head-catalog (gw-retire). The dated backup retiring a head leaves, and the way back from it. Retiring copies
// every entry splice laid (HeadFolderLayout) and the head's command link into `<state>/head-backups/<key>/<local time>/`
// with a manifest naming each by its original path, kind and sha256, and only once all of it is there does it remove
// them, so a retirement that cannot finish its backup changes nothing. Restoring puts each back exactly as it was (a
// file's bytes and mode, a link's target, a dir), and first refuses, naming it, any path that now holds something
// other than what retiring left there: restore never writes over what the person changed since. The backup stays.
package splice.app.retire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.client.ClaudeModelLists
import splice.client.HeadFolderLayout
import splice.client.LaidKind
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

/** One entry of a backup: where it was, what it was, its sha256 (a file's bytes, a link's target), where the backup
 *  holds it, and for the file retiring edited, the sha256 of what it left there. */
@Serializable
internal data class BackupEntry(
    val path: String,
    val kind: String,
    val sha256: String? = null,
    val target: String? = null,
    val stored: String? = null,
    @SerialName("left_sha256") val leftSha256: String? = null,
)

/** A backup's manifest: the head, when it was retired, the lane `claude` was on when its head was retired, and every
 *  entry. */
@Serializable
internal data class BackupManifest(
    val key: String,
    val command: String,
    @SerialName("retired_at") val retiredAt: String,
    val lane: String? = null,
    val entries: List<BackupEntry> = emptyList(),
)

/** What retiring moves for one head: its command [link] (null when splice has none), the [laid] entries of its
 *  [folder], children before the dir that holds them, and the folder's .claude.json [state] to edit (null to leave
 *  it). */
internal data class RetirePlan(
    val folder: Path?,
    val link: Path?,
    val laid: List<Pair<Path, LaidKind>>,
    val state: Path?,
)

/** A retirement done: its [backup], and the paths that would not go ([stuck]), which the backup holds too. */
internal data class Retired(val backup: Path, val stuck: List<String>)

internal class HeadBackup(private val root: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** Backs up [plan] for [key], then removes what it holds, and answers the backup's folder. [lane] is the lane
     *  `claude` was on, for the head `claude` runs on. A failure before anything is removed leaves no backup. */
    fun retire(key: String, command: String, plan: RetirePlan, lane: String?): Retired {
        val now = LocalDateTime.now()
        val dir = fresh(root.resolve(key), now.format(STAMP))
        val entries = try {
            backUp(dir, plan)
        } catch (failure: java.io.IOException) {
            dir.toFile().deleteRecursively()
            throw failure
        }
        val manifest = BackupManifest(key, command, now.toString(), lane, entries.map { it.first })
        val body = json.encodeToString(BackupManifest.serializer(), manifest)
        SecureFile.writeAtomic0600(dir.resolve(BACKUP_MANIFEST), body)
        val stuck = entries.filterNot { (entry, edited) -> removed(entry, edited) }.map { it.first.path }
        return Retired(dir, stuck)
    }

    /** The manifest of the backup at [dir], or null when it cannot be read. */
    fun manifest(dir: Path): BackupManifest? = try {
        json.decodeFromString(BackupManifest.serializer(), Files.readString(dir.resolve(BACKUP_MANIFEST)))
    } catch (_: java.io.IOException) {
        null
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    }

    /** The paths that hold something now other than what retiring left there, which restore will not write over. */
    fun conflicts(manifest: BackupManifest): List<String> = manifest.entries.filter { !restorable(it) }.map { it.path }

    /** Puts back every entry of [manifest] from [dir], parents first; an entry already as it was is left. */
    fun restore(dir: Path, manifest: BackupManifest) {
        for (entry in manifest.entries.sortedBy { Path.of(it.path).nameCount }) {
            val path = Path.of(entry.path)
            when (entry.kind) {
                DIR -> Files.createDirectories(path)
                LINK -> if (!Files.exists(path, NOFOLLOW_LINKS)) {
                    Files.createSymbolicLink(path, Path.of(entry.target.orEmpty()))
                }
                // a file already there is the same file: conflicts() refused every other
                else -> if (entry.kind == EDITED || !Files.exists(path, NOFOLLOW_LINKS)) {
                    val from = dir.resolve(entry.stored.orEmpty())
                    Files.copy(from, path, REPLACE_EXISTING, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
                }
            }
        }
    }

    /** Each entry copied into [dir], with the edited .claude.json's new content beside it (null for every other). */
    private fun backUp(dir: Path, plan: RetirePlan): List<Pair<BackupEntry, String?>> {
        val out = mutableListOf<Pair<BackupEntry, String?>>()
        plan.laid.forEach { (path, kind) -> out.add(stored(dir, inFolder(plan, path), path, kind) to null) }
        plan.state?.let { state -> edited(dir, inFolder(plan, state), state)?.let { out.add(it) } }
        plan.link?.let { link -> out.add(stored(dir, "$BIN/${link.fileName}", link, LaidKind.LINK) to null) }
        return out
    }

    private fun stored(dir: Path, name: String, path: Path, kind: LaidKind): BackupEntry {
        val into = dir.resolve(name)
        Files.createDirectories(into.parent)
        return when (kind) {
            LaidKind.DIR -> BackupEntry(path.toString(), DIR).also { Files.createDirectories(into) }
            LaidKind.LINK -> {
                val target = Files.readSymbolicLink(path)
                Files.createSymbolicLink(into, target)
                BackupEntry(path.toString(), LINK, sha(target.toString().toByteArray()), target.toString(), name)
            }
            LaidKind.FILE -> {
                Files.copy(path, into, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
                BackupEntry(path.toString(), FILE_KIND, sha(path), stored = name)
            }
        }
    }

    /** The .claude.json at [state] kept whole, and what it holds less splice's keys; null when it holds none. */
    private fun edited(dir: Path, name: String, state: Path): Pair<BackupEntry, String>? {
        val original = ClaudeModelLists.read(state) ?: return null
        val kept = JsonObject(original.filterKeys { it !in HeadFolderLayout.stateKeys })
        if (kept.size == original.size) return null
        Files.copy(state, dir.resolve(name).also { Files.createDirectories(it.parent) }, COPY_ATTRIBUTES)
        val left = json.encodeToString(JsonObject.serializer(), kept) + "\n"
        return BackupEntry(state.toString(), EDITED, sha(state), stored = name, leftSha256 = sha(left.toByteArray())) to
            left
    }

    /** Whether [entry] is gone from where it was, or for the edited .claude.json, holds [edited] now. */
    private fun removed(entry: BackupEntry, edited: String?): Boolean = try {
        val path = Path.of(entry.path)
        if (edited != null) SecureFile.writeAtomic0600(path, edited) else Files.deleteIfExists(path)
        true
    } catch (_: java.io.IOException) {
        false
    }

    /** Where the backup holds [path]: by its path in the head's folder, or by its name for the command link. */
    private fun inFolder(plan: RetirePlan, path: Path): String =
        "$FOLDER_KIND/" + (plan.folder?.relativize(path)?.toString() ?: path.fileName.toString())

    private fun restorable(entry: BackupEntry): Boolean {
        val path = Path.of(entry.path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return true
        return when (entry.kind) {
            DIR -> path.isDirectory(NOFOLLOW_LINKS)
            LINK -> path.isSymbolicLink() && Files.readSymbolicLink(path).toString() == entry.target
            EDITED -> !path.isSymbolicLink() && sha(path) == entry.leftSha256
            else -> !path.isSymbolicLink() && sha(path) == entry.sha256
        }
    }

    /** [parent]/[stamp], or with `-2`, `-3`... when a backup of the same second is there already. */
    private fun fresh(parent: Path, stamp: String): Path {
        var n = 1
        var dir = parent.resolve(stamp)
        while (Files.exists(dir, NOFOLLOW_LINKS)) dir = parent.resolve("$stamp-${++n}")
        return Files.createDirectories(dir)
    }

    private fun sha(path: Path): String = sha(Files.readAllBytes(path))

    private fun sha(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

private const val BACKUP_MANIFEST = "manifest.json"
private const val FOLDER_KIND = "folder"
private const val BIN = "bin"
private const val FILE_KIND = "file"
private const val LINK = "link"
private const val DIR = "dir"
private const val EDITED = "edited"

// why: the backup's folder name, local time to the second, in a form every file system takes.
private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
