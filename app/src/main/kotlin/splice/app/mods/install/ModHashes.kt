package splice.app.mods.install

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat

@Serializable
private data class HashRecord(
    val files: Map<String, String>,
    @SerialName("file_count") val fileCount: Int,
    @SerialName("installed_at") val installedAt: String,
    val source: String,
)

internal sealed class HashVerdict {
    data object Verified : HashVerdict()
    data object Linked : HashVerdict()
    data class Refused(val why: String) : HashVerdict()
}

/** The per-file record lives beside the installed copy, never inside the files it describes. */
internal class ModHashes(private val clock: Clock = Clock.systemUTC()) {
    fun record(root: Path, file: Path, source: String) {
        val files = HashTree(root).read()
        val record = HashRecord(files, files.size, clock.instant().toString(), source)
        SecureFile.writeAtomic0600(file, Json.encodeToString(record))
    }

    fun verify(mod: InstalledMod): HashVerdict {
        if (mod.linked) return HashVerdict.Linked
        val findings = try {
            val expected = Json.decodeFromString<HashRecord>(Files.readString(mod.hashFile)).files
            delta(expected, HashTree(mod.root).read())
        } catch (unreadable: IOException) {
            "could not check installed files: ${unreadable.message}"
        } catch (_: IllegalArgumentException) {
            "the installed file record is unreadable"
        }
        return if (findings == null) {
            HashVerdict.Verified
        } else {
            HashVerdict.Refused(
                "$findings. To accept the files as they are now: splice mod enable ${mod.name} --accept. " +
                    "To restore the original files, reinstall from ${mod.source}.",
            )
        }
    }

    private fun delta(before: Map<String, String>, after: Map<String, String>): String? {
        val changes = (before.keys + after.keys).sorted().mapNotNull { path ->
            when {
                path !in before -> "$path (added)"
                path !in after -> "$path (missing)"
                before[path] != after[path] -> "$path (changed)"
                else -> null
            }
        }
        if (changes.isEmpty()) return null
        // Keep a refusal readable even if the whole tree was replaced.
        return "files changed since install: ${changes.joinToString(limit = 8)}"
    }
}

private class HashTree(root: Path) : ModTree(root, ModBudget()) {
    private val found = sortedMapOf<String, String>()
    private val digest = MessageDigest.getInstance("SHA-256")
    private val sink = DigestOutputStream(OutputStream.nullOutputStream(), digest)

    fun read(): Map<String, String> {
        walk()
        return found
    }

    override fun file(relative: Path, path: Path) {
        Files.newInputStream(path, NOFOLLOW_LINKS).use { budget.transfer(it, sink) }
        found[relative.toString()] = HexFormat.of().formatHex(digest.digest())
    }
}
