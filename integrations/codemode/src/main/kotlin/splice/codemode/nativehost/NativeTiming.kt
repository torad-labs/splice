package splice.codemode.nativehost

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.codemode.NativeCodeModeTiming
import java.io.IOException
import java.time.Duration

// why: Protocol V1 durations are unsigned nanoseconds; splitting retains the complete u64 range.
private const val NATIVE_NANOS_PER_SECOND: ULong = 1_000_000_000uL
internal const val NATIVE_UINT64_BITS: Int = 64

internal object NativeTiming {
    fun decode(payload: JsonObject, completed: Boolean): NativeCodeModeTiming = NativeCodeModeTiming(
        hostDuration = duration(payload, "code_mode_host_duration_ns"),
        resultBufferedFor = if (completed) duration(payload, "result_buffered_for_ns") ?: Duration.ZERO else null,
    )

    private fun duration(payload: JsonObject, name: String): Duration? {
        val encoded = JsonScalars.str(payload, name) ?: return null
        val nanos = encoded.toULongOrNull() ?: throw IOException("Invalid native host duration: $name")
        return Duration.ofSeconds(
            (nanos / NATIVE_NANOS_PER_SECOND).toLong(),
            (nanos % NATIVE_NANOS_PER_SECOND).toLong(),
        )
    }
}
