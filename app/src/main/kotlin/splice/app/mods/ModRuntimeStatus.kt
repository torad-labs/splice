package splice.app.mods

/** Runner-owned status. Reads take no file or process I/O; a change asks the runner to reconcile the roster. */
internal interface ModRuntimeStatus {
    val handoverDropped: Long get() = 0
    fun of(name: String): ModRuntime?
    fun changed(name: String)
    suspend fun settled(name: String) = Unit
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
    val pid: Long? = null,
    val loaded: ModLoadFacts? = null,
    val diagnostics: ModDiagnostics = ModDiagnostics(),
)

internal data class ModLoadFacts(
    val notice: String,
    val served: Set<String>,
    val refused: List<String>,
    val declaredNotServed: List<String>,
    val bunVersion: String?,
)

internal data class ModDiagnostics(
    val droppedByEvent: Map<String, Long> = emptyMap(),
    val droppedTransport: Long = 0,
    val droppedLogLines: Long = 0,
    val restartsInWindow: Int = 0,
    val lastError: String? = null,
    val bunPath: String? = null,
)

internal object PendingMods : ModRuntimeStatus {
    override fun of(name: String): ModRuntime? = null
    override fun changed(name: String) = Unit
}
