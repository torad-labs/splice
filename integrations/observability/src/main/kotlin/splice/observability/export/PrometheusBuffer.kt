package splice.observability.export

/** A fixed buffer checks the complete fragment before writing; no geometric growth or partial success. */
internal class PrometheusBuffer {
    private val bytes = ByteArray(PerfScrapeLimits.MAX_TEXT_BYTES)
    private var length = 0
    var exceeded: Boolean = false
        private set

    fun append(value: String): PrometheusBuffer {
        if (exceeded || value.length > bytes.size - length) { exceeded = true; return this }
        value.forEach { bytes[length++] = it.code.toByte() }
        return this
    }

    fun append(value: Char): PrometheusBuffer = append(value.toString())
    fun append(value: Long): PrometheusBuffer = append(value.toString())
    fun append(value: Double): PrometheusBuffer = append(value.toString())
    fun text(): String? = if (exceeded) null else String(bytes, 0, length, Charsets.US_ASCII)
}
