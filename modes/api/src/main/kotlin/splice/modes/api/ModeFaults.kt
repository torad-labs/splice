// NEW: one mode's findings while the catalog checks it, each "mode <name>: <what is wrong>".
package splice.modes.api

/** Where a manifest's findings go, each the sentence after "mode <name>: ". */
internal fun interface ManifestFault {
    fun report(what: String)
}

/** The findings of mode [mode], in the order they were found. */
internal class ModeFaults(private val mode: String) : ManifestFault {
    private val found = mutableListOf<String>()

    val list: List<String> get() = found.toList()

    fun any(): Boolean = found.isNotEmpty()

    /** How many findings there are so far: a reader compares it before and after a part to know the part is whole. */
    val size: Int get() = found.size

    override fun report(what: String) {
        found += "mode $mode: $what"
    }
}
