// NEW: the JVM runtime supplies one process ledger without an OS escape from core.
package splice.upstream.memory

import splice.core.memory.HeapBudget

/** JVM adapter for the explicit heap-limit input to the framework-free ledger. */
public object JvmHeap {
    public val limitBytes: Long = Runtime.getRuntime().maxMemory()

    /** All production listeners and retained owners spend this same ledger. */
    public val budget: HeapBudget = HeapBudget(limitBytes)
}
