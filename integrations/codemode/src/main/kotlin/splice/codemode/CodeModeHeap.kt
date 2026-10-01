// NEW: one configured memory budget backs per-context retained-heap limits and the shared isolate heap.
package splice.codemode

internal object CodeModeHeap {
    // why: reserve a quarter of the configured host heap for framing, engine metadata and host callbacks.
    private const val HOST_RESERVE_DIVISOR = 4

    fun guestBytes(): Long {
        val configured = Runtime.getRuntime().maxMemory()
        return configured - configured / HOST_RESERVE_DIVISOR
    }
}
