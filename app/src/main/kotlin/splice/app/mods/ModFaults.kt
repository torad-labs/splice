// NEW: a mod folder's findings while ModFolderCheck reads it, each a sentence naming the file, key or entry at fault,
// never quoting a value except a name; every fault is kept, so a person fixes them all in one pass.
package splice.app.mods

/** The findings about one mod folder, in the order they were found. */
internal class ModFaults {
    private val found = mutableListOf<String>()

    val list: List<String> get() = found.toList()

    fun any(): Boolean = found.isNotEmpty()

    fun add(what: String) {
        found += what
    }
}
