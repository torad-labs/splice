package splice.app.mods

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.app.mods.install.ModRefusal
import splice.core.util.JsonScalars
import splice.core.wire.TOOL_NAME_MAX_LENGTH
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal const val MOD_MANIFEST_PATH = ".splice-mod/mod.json"
private const val MOD_HOOKS_PATH = "hooks/hooks.json"
private const val MOD_DESCRIPTION_FIELD = "description"

// why: bound JSON and tool descriptions independently of the package's total file budget.
private const val METADATA_LIMIT = 1024 * 1024
private val topKeys = setOf(
    "name",
    "version",
    MOD_DESCRIPTION_FIELD,
    "author",
    "surfaces",
    "capabilities",
    "tools",
    "pages",
    "commands",
    "views",
)
private const val VERSION_NUMBER = """(?:0|[1-9][0-9]*)"""
private const val PRE_RELEASE_PART = """(?:$VERSION_NUMBER|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"""
private val semver = Regex(
    """$VERSION_NUMBER\.$VERSION_NUMBER\.$VERSION_NUMBER(?:-$PRE_RELEASE_PART(?:\.$PRE_RELEASE_PART)*)?""" +
        """(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""",
)

/** Validates the package and retains its own declaration for the catalog, not a second manifest type. */
internal data class ModRead(val manifest: JsonObject?, val faults: List<String>) {
    fun requireManifest(): JsonObject = manifest ?: throw ModRefusal(faults.joinToString("\n"))
}

/** Checks the mod whose root is [root], naming every invalid field or missing file in one answer. */
internal class ModFolderCheck(private val root: Path) {
    private val faults = ModFaults()

    fun check(): ModRead {
        val obj = manifestObject() ?: return ModRead(null, faults.list)
        val keys = ModKeys(obj, faults, "mod.json")
        keys.unknown(topKeys)
        val name = keys.string("name")?.also(::checkName)
        val version = keys.string("version")
        if (version != null && !semver.matches(version)) faults.add("mod.json's version is not a semantic version")
        keys.string(MOD_DESCRIPTION_FIELD)
        keys.optionalString("author")
        keys.strings("surfaces")
        keys.strings("capabilities").forEach(::checkCapability)
        ModDeclaredRead(this, faults, keys).check(name)
        hooks()
        return ModRead(obj.takeUnless { faults.any() }, faults.list)
    }

    private fun manifestObject(): JsonObject? {
        if (!hasFile(MOD_MANIFEST_PATH)) {
            return null.also { faults.add("there is no $MOD_MANIFEST_PATH in it, so it is not a mod") }
        }
        return json(
            MOD_MANIFEST_PATH,
        ) as? JsonObject ?: null.also { faults.add("$MOD_MANIFEST_PATH is not a JSON object") }
    }

    fun text(relative: String): String? {
        val file = realFile(relative) ?: return null
        return try {
            Files.newInputStream(file).use { input ->
                val bytes = input.readNBytes(METADATA_LIMIT + 1)
                if (bytes.size <= METADATA_LIMIT) {
                    bytes.decodeToString(throwOnInvalidSequence = true)
                } else {
                    faults.add("$relative exceeds the 1 MiB metadata limit")
                    null
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    fun json(relative: String): JsonElement? = text(relative)?.let { text ->
        try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            null
        }
    }

    fun hasFile(relative: String): Boolean = realFile(relative) != null

    fun stays(relative: String): Boolean = inside(root, relative) != null

    private fun realFile(relative: String): Path? {
        val resolved = inside(root, relative) ?: return null
        return try {
            resolved.toRealPath().takeIf { it.startsWith(root.toRealPath()) && Files.isRegularFile(it) }
        } catch (_: IOException) {
            null
        }
    }

    private fun inside(root: Path, relative: String): Path? {
        val base = root.normalize()
        val path = try {
            root.fileSystem.getPath(relative)
        } catch (_: InvalidPathException) {
            return null
        }
        if (path.isAbsolute || relative.isEmpty()) return null
        return base.resolve(path).normalize().takeIf { it.startsWith(base) && it != base }
    }

    private fun checkName(name: String) {
        if (!ModNames.isModName(name)) faults.add("mod.json's name $name is not ${ModNames.MOD_RULE}")
    }

    private fun checkCapability(capability: String) {
        if (capability !in ModNames.capabilities) {
            faults.add(
                "mod.json's capabilities has $capability, which is none of " +
                    ModNames.capabilities.sorted().joinToString(", "),
            )
        }
    }

    private fun hooks() {
        if (!Files.exists(root.resolve(MOD_HOOKS_PATH))) return
        val obj = json(MOD_HOOKS_PATH) as? JsonObject ?: return faults.add("$MOD_HOOKS_PATH is not a JSON object")
        val keys = ModKeys(obj, faults, MOD_HOOKS_PATH)
        keys.unknown(setOf("modules"))
        if ("modules" !in obj) return faults.add("$MOD_HOOKS_PATH has no modules")
        keys.strings("modules").forEach { module ->
            val path = "hooks/" + module.removePrefix("./")
            when {
                inside(root.resolve("hooks"), module) == null ->
                    faults.add("$MOD_HOOKS_PATH lists module $module, which is outside hooks/")
                !hasFile(path) -> faults.add("$MOD_HOOKS_PATH lists module $module, and $path is missing")
            }
        }
    }
}

/** The tools, pages, commands and views [keys] lists, each checked against [folder]'s files. */
private class ModDeclaredRead(
    private val folder: ModFolderCheck,
    private val faults: ModFaults,
    private val keys: ModKeys,
) {
    fun check(mod: String?) {
        keys.strings("tools").forEach { tool(it, mod) }
        keys.objects("pages").forEach { (place, obj) -> word(place, obj, "pages", "title") }
        keys.objects("commands").forEach { (place, obj) -> word(place, obj, "commands", MOD_DESCRIPTION_FIELD) }
        keys.objects("views").forEach { (place, obj) -> view(place, obj) }
    }

    private fun tool(name: String, mod: String?) {
        if (!ModNames.isToolName(name)) {
            faults.add("tools lists $name, which is not ${ModNames.MOD_TOOL_NAME_RULE}")
            return
        }
        schema(name)
        description(name)
        val meta = "tools/$name.meta.json"
        if (folder.hasFile(meta) && folder.json(meta) !is JsonObject) faults.add("$meta is not a JSON object")
        if (mod != null) offered(mod, name)
    }

    private fun description(name: String) {
        val file = "tools/$name.md"
        val text = folder.text(file)?.trim() ?: return faults.add("tools lists $name, and $file is missing")
        if (text.isEmpty()) faults.add("$file is empty")
    }

    private fun offered(mod: String, name: String) {
        val offered = ModNames.offered(mod, name)
        if (offered.length > TOOL_NAME_MAX_LENGTH) {
            faults.add("tool $name is offered as $offered, longer than ${TOOL_NAME_MAX_LENGTH} characters")
        }
    }

    private fun schema(name: String) {
        val file = "tools/$name.json"
        if (!folder.hasFile(file)) return faults.add("tools lists $name, and $file is missing")
        val schema = folder.json(file) as? JsonObject
        if (JsonScalars.str(schema, "type") != "object") {
            faults.add("$file is not a JSON Schema object with type object")
        }
    }

    private fun word(place: Int, obj: JsonObject, kind: String, needed: String) {
        val where = "$kind entry $place"
        val keys = ModKeys(obj, faults, where)
        keys.unknown(setOf("name", needed))
        val name = keys.string("name")
        if (name != null && !ModNames.isWord(name)) faults.add("$where's name $name is not ${ModNames.WORD_RULE}")
        keys.string(needed)
    }

    private fun view(place: Int, obj: JsonObject) {
        val where = "views entry $place"
        val keys = ModKeys(obj, faults, where)
        keys.unknown(setOf("uri", "name", "mimeType", "file"))
        val uri = keys.string("uri")
        if (uri != null && !uri.startsWith("ui://")) faults.add("$where's uri does not start with ui://")
        keys.string("name")
        keys.optionalString("mimeType")
        val file = keys.optionalString("file") ?: return
        when {
            !folder.stays(file) -> faults.add("$where's file $file is outside the mod's folder")
            !folder.hasFile(file) -> faults.add("$where names file $file, which is missing")
        }
    }
}
