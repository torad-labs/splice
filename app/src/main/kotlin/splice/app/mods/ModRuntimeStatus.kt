package splice.app.mods

/** Runner-owned status. Reads take no file or process I/O; a change asks the runner to reconcile the roster. */
internal interface ModRuntimeStatus {
    fun of(name: String): ModRuntime?
    fun changed(name: String)
}

internal enum class ModRuntimeState(val wire: String) {
    STARTING("starting"),
    RUNNING("running"),
    RESTARTING("restarting"),
    STUCK("stuck"),
    FAILED("failed"),
    DISABLED("disabled"),
}

internal data class ModRuntime(
    val state: ModRuntimeState,
    val why: String? = null,
    val dropped: Long = 0,
)

internal object PendingMods : ModRuntimeStatus {
    override fun of(name: String): ModRuntime? = null
    override fun changed(name: String) = Unit
}
