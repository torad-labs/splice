package splice.upstream.codemode

import java.math.BigInteger

// why: The keyless compatibility API has always shared this session; addressed callers supply their own key.
private const val NATIVE_UNADDRESSED_SESSION_KEY = "unaddressed-native-runtime"

/** Foreground observation budgets passed unchanged to the pinned native runtime. */
public data class NativeCodeModeOptions(
    val yieldTimeMs: Long = 10_000,
    val maxOutputTokens: Long = 10_000,
    /** Exact u64 controls when a model supplied a value outside the compatibility Long domain. */
    val unsignedYieldTimeMs: BigInteger? = null,
    val unsignedMaxOutputTokens: BigInteger? = null,
    /** The model's outer exec identity, retained by host notifications and callback tracing. */
    val toolCallId: String? = null,
) {
    init {
        require(yieldTimeMs >= 0) { "Native observation time must be nonnegative" }
        require(maxOutputTokens >= 0) { "Native output token budget must be nonnegative" }
        require(unsignedYieldTimeMs == null || unsignedYieldTimeMs in BigInteger.ZERO..MAX_NATIVE_UNSIGNED) {
            "Native observation time exceeds u64"
        }
        require(unsignedMaxOutputTokens == null || unsignedMaxOutputTokens in BigInteger.ZERO..MAX_NATIVE_UNSIGNED) {
            "Native output token budget exceeds u64"
        }
    }
}

/** Optional richer start port; legacy runtimes retain their existing CodeModeRuntime contract. */
public interface NativeCodeModeLifecycle : CodeModeRuntime {
    override suspend fun start(
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell = startNativeSession(
        NATIVE_UNADDRESSED_SESSION_KEY,
        source,
        tools,
        descriptions,
        NativeCodeModeOptions(),
    )

    /** Opens the real native session without evaluating a JavaScript module. */
    public suspend fun openNativeSession(sessionKey: String): Unit = Unit

    /** Observe or terminate an id through its owning native session, without executing another module. */
    public suspend fun controlNativeSession(sessionKey: String, cellId: String): NativeCodeModeCell? = null

    public suspend fun startNativeSession(
        sessionKey: String,
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell

    /** Complete per-execution definitions; compatibility runtimes retain the original catalog port. */
    public suspend fun startNativeSession(
        sessionKey: String,
        source: String,
        definitions: List<CodeModeManual.NestedTool>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell = startNativeSession(
        sessionKey,
        source,
        definitions.map { it.codeModeName }.toSet(),
        definitions.associate { it.codeModeName to it.description },
        options,
    )
}

/** Native cells survive foreground observations and are terminated only by an explicit lifecycle action. */
public interface NativeCodeModeCell : CodeModeCell {
    public val cellId: String

    /** Resolves callbacks independently of an outstanding foreground observation. */
    public suspend fun submitResults(results: List<CodeModeResult>)
    public suspend fun terminate(): CodeModeStep

    /** The native protocol accepts the entire unsigned 64-bit millisecond domain. */
    public suspend fun observe(yieldTimeMs: BigInteger): CodeModeStep = observe(yieldTimeMs.longValueExact())

    /** Yield the current foreground observation while the cell and its nested calls keep running. */
    public suspend fun yieldObservation(): Unit = Unit
}

private val MAX_NATIVE_UNSIGNED = CodeModeUnsigned.maximum
