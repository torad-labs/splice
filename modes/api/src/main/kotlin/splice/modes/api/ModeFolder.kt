// NEW: one mode's own files as the catalog reads them, from its resources under splice-modes/<folder>/ (the
// splice.mode plugin lays them there), so the shadow jar never merges two modes' files.
package splice.modes.api

/** A mode's folder, read through [loader]: [read] gives a file's text by its path inside the folder, or null. */
internal class ModeFolder(val name: String, private val loader: ClassLoader) {
    fun read(path: String): String? =
        loader.getResource("$MODE_RESOURCES/$name/$path")?.openStream()?.use { it.readBytes().decodeToString() }

    fun has(path: String): Boolean = loader.getResource("$MODE_RESOURCES/$name/$path") != null
}

/** Where every mode's files sit in its jar, and the index naming each mode's folder. */
internal const val MODE_RESOURCES = "splice-modes"
internal const val MODE_INDEX = "$MODE_RESOURCES/index"
