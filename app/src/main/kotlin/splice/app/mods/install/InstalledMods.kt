package splice.app.mods.install

import kotlinx.serialization.json.Json
import splice.app.mods.ModFolderCheck
import splice.app.mods.ModNames
import splice.app.mods.ModRuntimeStatus
import splice.app.mods.PendingMods
import splice.app.mods.sessions.InstalledModNames
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The daemon's installed roster. Readers see a snapshot; only management writes stage files and replace it. */
internal class InstalledMods(paths: StatePaths) : InstalledModNames {
    private val directory = paths.modsDir.toAbsolutePath().normalize()
    private val installed = directory.resolve("installed")
    private val file = directory.resolve("mods.json")
    private val hashes = ModHashes()
    internal val bun = ModBun(paths.envReader)

    @Volatile
    var runtime: ModRuntimeStatus = PendingMods
    private val loaded = Cancellables.runCatchingBestEffort { read() }
    val unreadable: String? = loaded.exceptionOrNull()?.let {
        "could not read the installed mod roster at $file: ${SafeFailureText.render(it)}"
    }

    @Volatile
    private var records = loaded.getOrNull().orEmpty()

    fun all(): List<InstalledMod> = records.values.map { InstalledMod(it, installed) }

    fun find(name: String): InstalledMod? = records[name]?.let { InstalledMod(it, installed) }

    override fun names(): Set<String> = records.keys.toSet()

    @Synchronized
    fun install(input: ModInstall): InstalledMod {
        ready()
        val source = source(input)
        if (input.linked) {
            val record = ModRecord(
                manifest = ModFolderCheck(source).check().requireManifest(),
                source = source.toString(),
                linkedPath = source.toString(),
                bunPath = input.bunPath,
            )
            val mod = InstalledMod(record, installed)
            // Replacing a copied mod with a link removes only the managed copy.
            if (find(mod.name)?.linked == false) {
                staging().use { it.publish(mod, payload = null) { save(mod) } }
            } else {
                save(mod)
            }
            return mod
        }
        return staging().use { staged ->
            val root = staged.unpack(source)
            val manifest = ModFolderCheck(root).check().requireManifest()
            val mod = InstalledMod(ModRecord(manifest, source.toString(), bunPath = input.bunPath), installed)
            hashes.record(root, staged.hash, mod.source)
            staged.publish(mod, root) { save(mod) }
            mod
        }
    }

    @Synchronized
    fun switch(name: String, requested: ModSwitch): InstalledMod {
        val current = requireMod(name)
        if (!requested.enabled) return save(InstalledMod(current.record.copy(enabled = false), installed))
        if (!requested.accept) {
            val verdict = current.verify()
            if (verdict is HashVerdict.Refused) throw ModRefusal(verdict.why, ModRefusal.Kind.CONFLICT)
        }
        val updated = if (requested.accept || current.linked) refreshManifest(current) else current.record
        val mod = InstalledMod(updated.copy(enabled = true), installed)
        if (requested.accept && !mod.linked) {
            staging().use { staged ->
                hashes.record(mod.root, staged.hash, mod.source)
                staged.accept(mod) { save(mod) }
            }
        } else {
            save(mod)
        }
        return mod
    }

    @Synchronized
    fun remove(name: String): InstalledMod {
        val mod = requireMod(name)
        if (mod.linked) {
            persist(records - name)
        } else {
            staging().use { it.publish(mod, payload = null) { persist(records - name) } }
        }
        return mod
    }

    private fun ready() {
        unreadable?.let { throw IOException(it) }
    }

    private fun requireMod(name: String): InstalledMod {
        ready()
        return find(name)
            ?: throw ModRefusal("no installed mod named $name; see splice mod list", ModRefusal.Kind.MISSING)
    }

    private fun source(input: ModInstall): Path {
        val source = input.source
        val folder = Files.isDirectory(source, NOFOLLOW_LINKS)
        val file = Files.isRegularFile(source, NOFOLLOW_LINKS)
        val tarball = file && !input.linked
        if (!folder && !tarball) {
            val expected = if (input.linked) "a mod folder" else "a mod folder or tarball"
            throw ModRefusal("$source is not $expected; give the path to one")
        }
        val real = source.toRealPath()
        val includesState = folder && directory.startsWith(real)
        if (real.startsWith(directory) || includesState) {
            throw ModRefusal("choose a source outside the mod state folder that does not contain it")
        }
        return real
    }

    private fun refreshManifest(mod: InstalledMod): ModRecord {
        val declaration = ModFolderCheck(mod.root).check().requireManifest()
        if (JsonScalars.str(declaration, "name") != mod.name) {
            throw ModRefusal("mod.json names a different mod; reinstall it under its new name")
        }
        return mod.record.copy(manifest = declaration)
    }

    private fun staging(): ModStage {
        ModPrivateDir.create(directory)
        ModPrivateDir.create(installed)
        return ModStage(installed)
    }

    private fun save(mod: InstalledMod): InstalledMod {
        persist(records + (mod.name to mod.record))
        return mod
    }

    private fun persist(next: Map<String, ModRecord>) {
        ModPrivateDir.create(directory)
        SecureFile.writeAtomic0600(file, Json.encodeToString(next.values.toList()))
        records = next
    }

    private fun read(): Map<String, ModRecord> {
        val text = try {
            Files.readString(file)
        } catch (_: NoSuchFileException) {
            return emptyMap()
        }
        val stored = Json.decodeFromString<List<ModRecord>>(text)
        val named = stored.associateBy { it.name }
        if (named.size != stored.size || !named.keys.all(ModNames::isModName)) {
            throw IOException("mod names are invalid or duplicated")
        }
        return named
    }
}
