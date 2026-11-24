// NEW: upgrade-compat (Nov 23, 2026). A setting splice saved in its own state folder that it no longer reads (a retired
// knob in config.json, a daemon-wide knob in a head's config.json) used to refuse boot until a person ran the fix
// session, so a daemon started by its service, or by an upgrade, never came up over a file splice itself wrote. The
// daemon now clears each such key at its start, once it holds the lock: one copy of each file first, in splice's config
// backups and named as the fix session names its copies, and one log line per key. A file it cannot copy is left whole.
package splice.topology

import splice.core.config.SavedSettings
import splice.core.topology.TopologyBackupName
import splice.core.topology.TopologyFinding
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// why: the same twelve hex characters the console writer and the fix session name their backups with.
private const val PRUNE_HASH_LENGTH = 12

public class SavedSettingsPrune(private val backups: Path) {
    /** Clears every saved setting [warnings] names (TopologyFinding.Origin.Saved), each file copied once first; the
     *  lines say what was copied and cleared, by file and key and never a value. */
    public fun prune(warnings: List<TopologyFinding>): List<String> =
        warnings.mapNotNull { it.origin as? TopologyFinding.Origin.Saved }.groupBy { it.file }
            .flatMap { (file, saved) -> pruned(file, saved.map { it.key }.distinct()) }

    private fun pruned(file: Path, keys: List<String>): List<String> {
        val copy = copied(file) ?: return listOf("[config] $file was left as it is: no copy of it could be made\n")
        return listOf("[config] copied $file to $copy before clearing what splice no longer reads\n") +
            keys.map { key ->
                if (SavedSettings(file).clear(key)) {
                    "[config] cleared $key from $file\n"
                } else {
                    "[config] $key was left in $file, which changed meanwhile\n"
                }
            }
    }

    private fun copied(file: Path): Path? = try {
        val bytes = Files.readAllBytes(file)
        val hash = TopologyLoader.sha256Hex(bytes).take(PRUNE_HASH_LENGTH)
        val _ = SecureFile.ownerOnlyDirectory(backups)
        backups.resolve(TopologyBackupName.of(file, Instant.now(), hash)).also { copy ->
            SecureFile.writeAtomic0600(copy, bytes.toString(Charsets.UTF_8))
        }
    } catch (_: IOException) {
        null
    }
}
