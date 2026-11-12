package splice.upstream.codemode

import java.time.Duration

/** Native measurements distinguish an unavailable value from a measured zero duration. */
public data class NativeCodeModeTiming(
    /** Time from host receipt to this observation, excluding work between observations. */
    val hostDuration: Duration? = null,
    /** Time a completed result spent waiting for retrieval; absent for a live or terminated cell. */
    val resultBufferedFor: Duration? = null,
)
