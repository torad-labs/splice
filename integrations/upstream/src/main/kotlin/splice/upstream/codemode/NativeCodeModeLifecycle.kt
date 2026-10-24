package splice.upstream.codemode

/** Foreground observation budgets passed unchanged to the pinned native runtime. */
public data class NativeCodeModeOptions(
    val yieldTimeMs: Long = 10_000,
    val maxOutputTokens: Long = 10_000,
) {
    init {
        require(yieldTimeMs >= 0) { "Native observation time must be nonnegative" }
        require(maxOutputTokens >= 0) { "Native output token budget must be nonnegative" }
    }
}

/** Optional richer start port; legacy runtimes retain their existing CodeModeRuntime contract. */
public interface NativeCodeModeLifecycle : CodeModeRuntime {
    /** Opens the real native session without evaluating a JavaScript module. */
    public suspend fun openNativeSession(sessionKey: String): Unit = Unit

    public suspend fun startNativeSession(
        sessionKey: String,
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell
}

/** Native cells survive foreground observations and are terminated only by an explicit lifecycle action. */
public interface NativeCodeModeCell : CodeModeCell {
    public val cellId: String

    /** Resolves callbacks independently of an outstanding foreground observation. */
    public suspend fun submitResults(results: List<CodeModeResult>)
    public suspend fun terminate(): CodeModeStep
}
