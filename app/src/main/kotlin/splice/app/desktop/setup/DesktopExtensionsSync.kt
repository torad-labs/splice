// NEW: desktop-policy, moved out of DesktopProfileSync.kt (desktop-3p-parity, Nov 23, 2026). The Desktop extensions
// (`.dxt`/`.mcpb`, unpacked under `Claude Extensions/`) the person installed in Claude Desktop that the gateway profile
// (`Claude-3p`) lacks, copied in with their settings and their entries in `extensions-installations.json`. An
// extension already in the profile is never touched. Each is copied as DesktopTreeCopy copies a folder, so its bundled
// programs keep their executable bit and its links stay inside it.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class DesktopExtensionsSync(private val files: DesktopLibraryFiles, private val tree: DesktopTreeCopy) {
    /** Missing extensions of [sourceAppData] copied into [targetAppData]; whether anything was. */
    fun sync(sourceAppData: Path, targetAppData: Path): Boolean {
        val srcExtRoot = sourceAppData.resolve(SYNC_EXTENSIONS_DIR)
        val sameRoot = sourceAppData.toAbsolutePath().normalize() == targetAppData.toAbsolutePath().normalize()
        val extDirs = if (!sameRoot && Files.isDirectory(srcExtRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(srcExtRoot).use { stream ->
                stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList()
            }
        } else {
            emptyList()
        }
        if (extDirs.isEmpty()) return false
        val copiedAny = extDirs.fold(false) { changed, srcDir ->
            val copied = copyMissingExtension(srcDir, sourceAppData, targetAppData)
            changed || copied
        }
        val registryChanged = syncRegistry(sourceAppData, targetAppData)
        return copiedAny || registryChanged
    }

    private fun copyMissingExtension(srcDir: Path, sourceAppData: Path, targetAppData: Path): Boolean {
        val extId = srcDir.fileName.toString()
        val extCopied = tree.copy(srcDir, targetAppData.resolve(SYNC_EXTENSIONS_DIR).resolve(extId))
        val srcSetting = sourceAppData.resolve(SYNC_EXTENSIONS_SETTINGS_DIR).resolve("$extId.json")
        val dstSetting = targetAppData.resolve(SYNC_EXTENSIONS_SETTINGS_DIR).resolve("$extId.json")
        val settingCopied = copyMissingFile(srcSetting, dstSetting)
        return extCopied || settingCopied
    }

    /** [src], an extension's settings, written to [dst] where there are none: owner-only, since they can hold what the
     *  person configured the extension with. */
    private fun copyMissingFile(src: Path, dst: Path): Boolean {
        val settings = src.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }?.let(files::read)
        if (settings == null || Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) return false
        dst.parent?.let(Files::createDirectories)
        files.write(dst, settings)
        return true
    }

    private fun syncRegistry(sourceAppData: Path, targetAppData: Path): Boolean {
        val srcFile = sourceAppData.resolve(SYNC_EXTENSIONS_REGISTRY)
        val srcExts = if (Files.isRegularFile(srcFile, LinkOption.NOFOLLOW_LINKS)) {
            files.read(srcFile)?.get(SYNC_EXTENSIONS_KEY) as? JsonObject
        } else {
            null
        }
        val targetExtDir = targetAppData.resolve(SYNC_EXTENSIONS_DIR)
        val eligible = srcExts.orEmpty().filterKeys { extId ->
            val installed = targetExtDir.resolve(extId).normalize()
            installed.parent == targetExtDir.normalize() && Files.isDirectory(installed, LinkOption.NOFOLLOW_LINKS)
        }
        val dstFile = targetAppData.resolve(SYNC_EXTENSIONS_REGISTRY)
        val current = files.updatable(dstFile)
        val mergedExts = JsonMapMerger.mergeMissing(current, JsonObject(eligible), SYNC_EXTENSIONS_KEY) ?: return false
        Files.createDirectories(targetAppData)
        files.write(dstFile, JsonObject(current + (SYNC_EXTENSIONS_KEY to mergedExts)))
        return true
    }
}

/** The entries of one JSON map another lacks. */
internal object JsonMapMerger {
    /** [current]'s map at [key] with the entries of [src] it lacks; null when it lacks none or [key] is not a map. */
    fun mergeMissing(current: JsonObject, src: JsonObject?, key: String): JsonObject? {
        val entries = src?.takeIf { it.isNotEmpty() } ?: return null
        val held = when (val at = current[key]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> at
            is JsonArray, is JsonPrimitive -> return null
        }
        val missing = entries.filterKeys { it !in held }
        return if (missing.isEmpty()) null else JsonObject(held + missing)
    }
}

private const val SYNC_EXTENSIONS_DIR = "Claude Extensions"
private const val SYNC_EXTENSIONS_SETTINGS_DIR = "Claude Extensions Settings"
private const val SYNC_EXTENSIONS_REGISTRY = "extensions-installations.json"
private const val SYNC_EXTENSIONS_KEY = "extensions"
