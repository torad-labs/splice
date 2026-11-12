// NEW: a session's isolate has its own heap cap; a bounded host count bounds aggregate guest heaps.
package splice.codemode

internal object CodeModeHeap {
    // why: warmed engine increments are ~16 MiB; four engines bound native heaps and execution lanes per host.
    const val maxEnginesPerHost: Int = 4

    // why: bound native execution stacks within each engine's reserve without sharing a session's lane with another.
    const val maxExecutionsPerSession: Int = 4

    // why: reuse compiled code briefly after completion, then reclaim idle isolates even without another turn.
    const val idleTimeoutMs: Long = 60_000

    // why: reserve a quarter of the configured host heap for framing, engine metadata and host callbacks.
    private const val HOST_RESERVE_DIVISOR = 4

    fun guestBytes(): Long = guestBytes(Runtime.getRuntime().maxMemory())

    fun guestBytes(configured: Long): Long = configured - configured / HOST_RESERVE_DIVISOR
}
