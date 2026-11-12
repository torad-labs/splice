package splice.hub.front

/** The two persistence actions every holder recovery transition needs. */
internal class HolderPersistence(
    private val changed: () -> Unit,
    private val checkpoint: () -> Boolean,
) {
    fun changed() {
        changed.invoke()
    }

    fun checkpoint(): Boolean = checkpoint.invoke()
}
