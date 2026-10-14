// NEW: the JVM runtime supplies one process ledger without an OS escape from core.
package splice.upstream.memory

import splice.core.memory.HeapBudget

/** JVM adapter for the explicit heap-limit input to the framework-free ledger. */
public object JvmHeap {
    public val limitBytes: Long = Runtime.getRuntime().maxMemory()

    /** Listeners and retained owners share at most half the heap; read views debit this same root. */
    public val budget: HeapBudget = HeapBudget(limitBytes)

    /** Diagnostic-only, off the request path. The control plane calls this on its existing file-I/O dispatcher. */
    public fun memorySnapshot(): JvmMemorySnapshot = JvmMemory.snapshot(budget.retentionSnapshot())
}
