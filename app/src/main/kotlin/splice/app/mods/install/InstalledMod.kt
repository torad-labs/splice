package splice.app.mods.install

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.nio.file.Path

@Serializable
internal data class ModRecord(
    val manifest: JsonObject,
    val source: String,
    val linkedPath: String? = null,
    val bunPath: String? = null,
    val enabled: Boolean = true,
) {
    val name: String get() = checkNotNull(JsonScalars.str(manifest, "name"))
}

/** A snapshot of one installed mod. For linked mods [root] is the author's folder. */
internal class InstalledMod(internal val record: ModRecord, private val installed: Path) {
    val name: String get() = record.name
    val manifest: JsonObject get() = record.manifest
    val enabled: Boolean get() = record.enabled
    val linked: Boolean get() = record.linkedPath != null
    val source: String get() = record.source
    val bunPath: String? get() = record.bunPath
    val root: Path get() = record.linkedPath?.let(Path::of) ?: copy
    internal val copy: Path get() = installed.resolve(name)
    internal val hashFile: Path get() = installed.resolve("$name.sha256.json")

    /** Call immediately before starting a mod; only an explicit linked install skips the check. */
    fun verify(): HashVerdict = ModHashes().verify(this)
}

internal data class ModInstall(val source: Path, val linked: Boolean, val bunPath: String?)

internal data class ModSwitch(val enabled: Boolean, val accept: Boolean)
