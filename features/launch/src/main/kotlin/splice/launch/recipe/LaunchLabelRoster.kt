// The launch roster's labels. A headless `claude -p` carries SPLICE_LABEL_* in the process that exec keeps, and
// the daemon's own environment is not that process's. They are read once, at /launch, and joined to a turn by the
// owner header that launch plants. The listing never reads them from a process.
package splice.launch.recipe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.client.desktop.GATEWAY_HEADERS
import splice.core.config.LAUNCH_OWNER_HEADER
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val OWNER_ID = Regex("[0-9a-fA-F-]{36}")
private const val LABEL_BYTES = 256
private const val MAX_LABELS = 16
private val LABEL_KEY = Regex("[A-Z][A-Z0-9_]{0,31}")

/** SPLICE_LABEL_* from a launch process's environment, or none when it cannot be read. */
public fun interface LaunchEnviron {
    public fun labelsOf(pid: Long): Map<String, String>
}

/** What one launch recorded: the labels it carried, the folder it started in, and the pid exec keeps. */
public data class LaunchHeld(
    val labels: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val pid: Long? = null,
)

/** Reads SPLICE_LABEL_* from /proc/<pid>/environ and keeps no other entry. Empty where /proc is absent. */
internal class ProcLaunchEnviron(private val proc: Path = Paths.get("/proc")) : LaunchEnviron {
    override fun labelsOf(pid: Long): Map<String, String> {
        if (pid <= 0) return emptyMap()
        val bytes = try {
            Files.readAllBytes(proc.resolve(pid.toString()).resolve("environ"))
        } catch (_: IOException) {
            return emptyMap()
        }
        return LabelFields.of(entries(bytes))
    }

    private fun entries(bytes: ByteArray): List<String> {
        val prefix = "SPLICE_LABEL_".toByteArray()
        val found = mutableListOf<String>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] != 0.toByte()) continue
            if (startsWith(bytes, start, prefix)) {
                found += bytes.copyOfRange(start + prefix.size, index).toString(Charsets.UTF_8)
            }
            start = index + 1
        }
        return found
    }

    private fun startsWith(bytes: ByteArray, start: Int, prefix: ByteArray): Boolean {
        if (start + prefix.size > bytes.size) return false
        return prefix.indices.all { bytes[start + it] == prefix[it] }
    }
}

/** One launch's labels, remembered by the owner header its recipe plants. */
public class LaunchLabelRoster(
    private val dir: Path? = null,
    private val environ: LaunchEnviron = ProcLaunchEnviron(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val byOwner = ConcurrentHashMap<String, LaunchHeld>()

    /** Records [fields] and the labels on [pid]'s environment, and returns the owner this launch plants. */
    public fun open(pid: String?, fields: List<String>, cwd: String?): String {
        val owner = UUID.randomUUID().toString()
        val labels = LabelFields.of(fields) + environ.labelsOf(pid?.toLongOrNull() ?: 0L)
        val held = LaunchHeld(labels, cwd, pid?.toLongOrNull())
        byOwner[owner] = held
        write(owner, held)
        return owner
    }

    /** The launch [owner] names, from memory or its one file. Null when this daemon never recorded it. */
    public fun held(owner: String): LaunchHeld? = byOwner[owner] ?: read(owner)?.also { byOwner[owner] = it }

    private fun write(owner: String, held: LaunchHeld) {
        val folder = dir ?: return
        val file = folder.resolve("$owner.json")
        val body = json.encodeToString(StoredLaunch(held.labels, held.cwd, held.pid))
        if (SecureFile.ownerOnlyDirectory(folder) == null) writeFile(file, body)
    }

    private fun read(owner: String): LaunchHeld? {
        val file = dir?.resolve("$owner.json")?.takeIf { OWNER_ID.matches(owner) }
        val text = file?.let(::textOf)
        val stored = text?.let(::decode)
        return stored?.let { LaunchHeld(it.labels, it.cwd, it.pid) }
    }

    /** A disk that refuses the write leaves the in-memory record. The next launch writes again. */
    private fun writeFile(file: Path, body: String) {
        try {
            SecureFile.writeAtomic0600(file, body)
        } catch (_: IOException) {
            return
        }
    }

    /** An unreadable file is no record. The listing never depends on it. */
    private fun textOf(file: Path): String? = try {
        Files.readString(file)
    } catch (_: IOException) {
        null
    }

    private fun decode(text: String): StoredLaunch? = try {
        json.decodeFromString(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Serializable
private data class StoredLaunch(
    val labels: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val pid: Long? = null,
)

/** Validates label fields and plants the owner header on a launch's custom headers. */
internal object LabelFields {
    fun of(fields: List<String>): Map<String, String> {
        val labels = mutableMapOf<String, String>()
        fields.forEach { offer(it, labels) }
        return labels
    }

    fun plant(env: Map<String, String>, owner: String?): Map<String, String> {
        val line = owner?.let { "$LAUNCH_OWNER_HEADER: $it" } ?: return env
        val existing = env[GATEWAY_HEADERS].orEmpty()
        val headers = listOf(existing, line).filter { it.isNotEmpty() }.joinToString("\n")
        return env + (GATEWAY_HEADERS to headers)
    }

    private fun offer(field: String, labels: MutableMap<String, String>) {
        val raw = field.removePrefix("SPLICE_LABEL_")
        val key = raw.substringBefore('=')
        val value = raw.substringAfter('=', "")
        if (rejected(key, raw, value, labels.size)) return
        labels.putIfAbsent(key.lowercase(), value)
    }

    private fun rejected(key: String, raw: String, value: String, held: Int): Boolean =
        !shaped(key, raw) || held == MAX_LABELS || !valid(value)

    private fun shaped(key: String, raw: String): Boolean = LABEL_KEY.matches(key) && '=' in raw

    private fun valid(value: String): Boolean = try {
        value.length <= LABEL_BYTES && !value.any(Char::isISOControl) &&
            value.encodeToByteArray(throwOnInvalidSequence = true).size <= LABEL_BYTES
    } catch (_: CharacterCodingException) {
        false
    }
}
