// NEW: one mod folder checked whole (the mods v2 contract, mods/README.md): `.splice-mod/mod.json` key by key, each
// listed tool's two files, each view's file, each page's and command's name, and `hooks/hooks.json`'s modules, with a
// finding for every fault at once, as a mode's folder is checked (ManifestCheck).
// No finding quotes a value except a name.
package splice.app.mods

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal const val MANIFEST = ".splice-mod/mod.json"
private const val HOOKS = "hooks/hooks.json"
private const val DESCRIPTION = "description"
private val TOP_KEYS = setOf(
    "name", "version", DESCRIPTION, "author", "surfaces", "capabilities", "tools", "pages", "commands", "views",
)

/** What a folder read as: the mod when it is whole, and every finding either way. */
internal data class ModRead(val manifest: ModManifest?, val faults: List<String>)

/** Checks the mod whose root is [root], the folder a person installs. */
internal class ModFolderCheck(private val root: Path) {
    private val faults = ModFaults()

    fun check(): ModRead {
        val obj = manifestObject() ?: return ModRead(null, faults.list)
        val keys = ModKeys(obj, faults, "mod.json")
        keys.unknown(TOP_KEYS)
        val name = keys.string("name")?.also(::checkName)
        val version = keys.string("version")?.also { if (it.isBlank()) faults.add("mod.json's version is empty") }
        val description = keys.string(DESCRIPTION)
        val author = keys.optionalString("author")
        val surfaces = keys.strings("surfaces")
        val capabilities = keys.strings("capabilities").onEach(::checkCapability)
        val declared = ModDeclaredRead(this, faults, keys).declared(name)
        hooks()
        if (faults.any()) return ModRead(null, faults.list)
        val manifest = ModManifest(
            checkNotNull(name),
            checkNotNull(version),
            checkNotNull(description),
            author,
            surfaces,
            capabilities,
            declared,
        )
        return ModRead(manifest, faults.list)
    }

    /** mod.json as an object, or null with the finding that says why it is not one. */
    private fun manifestObject(): JsonObject? {
        if (!Files.isRegularFile(root.resolve(MANIFEST))) {
            return null.also { faults.add("there is no $MANIFEST in it, so it is not a mod") }
        }
        return json(MANIFEST) as? JsonObject ?: null.also { faults.add("$MANIFEST is not a JSON object") }
    }

    /** The text of [relative] when it is a regular file inside the folder, else null. */
    fun text(relative: String): String? {
        val file = inside(relative) ?: return null
        if (!Files.isRegularFile(file)) return null
        return try {
            Files.readString(file)
        } catch (_: IOException) {
            null
        }
    }

    /** [relative] read as JSON, or null when it is missing or not JSON. */
    fun json(relative: String): JsonElement? = text(relative)?.let { text ->
        try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            null
        }
    }

    /** Whether [relative] names a path under the root, never above it or absolute. */
    fun stays(relative: String): Boolean = inside(relative) != null

    private fun inside(relative: String): Path? {
        val base = root.normalize()
        val path = try {
            root.fileSystem.getPath(relative)
        } catch (_: InvalidPathException) {
            null
        }
        val resolved = path?.takeUnless { it.isAbsolute || relative.isEmpty() }?.let { base.resolve(it).normalize() }
        return resolved?.takeIf { it.startsWith(base) && it != base }
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

    /** `hooks/hooks.json`, when the mod has one: `{ "modules": [...] }`, each module a file beside it. */
    private fun hooks() {
        if (!Files.exists(root.resolve(HOOKS))) return
        val obj = json(HOOKS) as? JsonObject ?: return faults.add("$HOOKS is not a JSON object")
        val keys = ModKeys(obj, faults, HOOKS)
        keys.unknown(setOf("modules"))
        if ("modules" !in obj) return faults.add("$HOOKS has no modules")
        keys.strings("modules").forEach { module ->
            val path = "hooks/" + module.removePrefix("./")
            when {
                !stays(path) -> faults.add("$HOOKS lists module $module, which is outside the mod's folder")
                text(path) == null -> faults.add("$HOOKS lists module $module, and $path is missing")
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
    fun declared(mod: String?): ModDeclared = ModDeclared(
        tools = keys.strings("tools").mapNotNull { tool(it, mod) },
        pages = keys.objects("pages").mapNotNull { (place, obj) -> page(place, obj) },
        commands = keys.objects("commands").mapNotNull { (place, obj) -> command(place, obj) },
        views = keys.objects("views").mapNotNull { (place, obj) -> view(place, obj) },
    )

    private fun tool(name: String, mod: String?): DeclaredTool? {
        if (!ModNames.isToolName(name)) {
            return null.also { faults.add("tools lists $name, which is not ${ModNames.TOOL_RULE}") }
        }
        val before = faults.size
        val schema = schema(name)
        val description = description(name)
        val meta = meta(name)
        mod?.let { offered(it, name) }
        if (faults.size > before) return null
        return DeclaredTool(name, checkNotNull(description), checkNotNull(schema), meta)
    }

    private fun description(name: String): String? {
        val file = "tools/$name.md"
        val text = folder.text(file)?.trim()
            ?: return null.also { faults.add("tools lists $name, and $file is missing") }
        return text.ifEmpty { null.also { faults.add("$file is empty") } }
    }

    private fun meta(name: String): JsonObject? {
        val file = "tools/$name.meta.json"
        folder.text(file) ?: return null
        return folder.json(file) as? JsonObject ?: null.also { faults.add("$file is not a JSON object") }
    }

    private fun offered(mod: String, name: String) {
        val offered = ModNames.offered(mod, name)
        if (offered.length > ModNames.MAX_OFFERED) {
            faults.add("tool $name is offered as $offered, longer than ${ModNames.MAX_OFFERED} characters")
        }
    }

    private fun schema(name: String): JsonObject? {
        val file = "tools/$name.json"
        if (folder.text(file) == null) return null.also { faults.add("tools lists $name, and $file is missing") }
        val schema = folder.json(file) as? JsonObject
        val typed = JsonScalars.str(schema, "type") == "object"
        if (!typed) faults.add("$file is not a JSON Schema object with type object")
        return schema.takeIf { typed }
    }

    private fun page(place: Int, obj: JsonObject): DeclaredPage? {
        val keys = ModKeys(obj, faults, "pages entry $place")
        keys.unknown(setOf("name", "title"))
        val name = word(keys, "pages entry $place")
        val title = keys.string("title")
        return if (name != null && title != null) DeclaredPage(name, title) else null
    }

    private fun command(place: Int, obj: JsonObject): DeclaredCommand? {
        val keys = ModKeys(obj, faults, "commands entry $place")
        keys.unknown(setOf("name", DESCRIPTION))
        val name = word(keys, "commands entry $place")
        val description = keys.string(DESCRIPTION)
        return if (name != null && description != null) DeclaredCommand(name, description) else null
    }

    private fun word(keys: ModKeys, where: String): String? {
        val name = keys.string("name") ?: return null
        if (ModNames.isWord(name)) return name
        return null.also { faults.add("$where's name $name is not ${ModNames.WORD_RULE}") }
    }

    private fun view(place: Int, obj: JsonObject): DeclaredView? {
        val where = "views entry $place"
        val keys = ModKeys(obj, faults, where)
        keys.unknown(setOf("uri", "name", "mimeType", "file"))
        val before = faults.size
        val uri = keys.string("uri")
        if (uri != null && !uri.startsWith("ui://")) faults.add("$where's uri does not start with ui://")
        val name = keys.string("name")
        val mime = keys.optionalString("mimeType")
        val file = keys.optionalString("file")
        if (file != null) {
            when {
                !folder.stays(file) -> faults.add("$where's file $file is outside the mod's folder")
                folder.text(file) == null -> faults.add("$where names file $file, which is missing")
            }
        }
        if (faults.size > before) return null
        return DeclaredView(checkNotNull(uri), checkNotNull(name), mime, file)
    }
}
