// NEW: a fixed head-wide memory reservation refuses new engines before allocating their native heaps.
package splice.codemode.host

import splice.codemode.CodeModeHeap
import splice.upstream.failure.CodeModeCapacityException

// why: constructor settings use binary MiB, matching JVM heap units and native heap reservations.
private const val MIB: Long = 1024L * 1024

// why: GraalVM's copying collector can consume twice its heap limit during a collection.
private const val COLLECTOR_FACTOR: Long = 2

// why: reserve host metadata, bounded thread stacks and protocol buffers outside the JVM heap.
private const val HOST_NATIVE_RESERVE_MB: Long = 128

// why: cover the measured ~16 MiB warmed-engine base plus four bounded execution stacks outside its guest heap.
private const val ENGINE_NATIVE_RESERVE_MB: Long = 64

/** Heap copying can double native RSS. Native reservations exceed the measured warmed base cost. */
internal class CodeModePoolAdmission(
    private val maxWorkers: Int,
    heapMb: Int,
    private val memoryBudgetMb: Long,
) {
    val budgetBytes: Long = Math.multiplyExact(memoryBudgetMb, MIB)
    private val hostBytes = heapMb.toLong() * MIB * COLLECTOR_FACTOR + HOST_NATIVE_RESERVE_MB * MIB
    private val engineBytes = CodeModeHeap.guestBytes(heapMb.toLong() * MIB) * COLLECTOR_FACTOR +
        ENGINE_NATIVE_RESERVE_MB * MIB

    fun reservedBytes(hosts: Int, engines: Int): Long =
        Math.addExact(Math.multiplyExact(hosts.toLong(), hostBytes), Math.multiplyExact(engines.toLong(), engineBytes))

    /** Called under the placement lock; retiring engines retain their reservation until acknowledged closed. */
    fun select(hosts: MutableList<CodeModePoolHost>): CodeModePoolHost? {
        val engines = hosts.sumOf(CodeModePoolHost::load)
        val least = hosts.minByOrNull(CodeModePoolHost::load)
        val occupied = least?.load() != 0
        val addHost = hosts.size < maxWorkers && occupied
        if (addHost && fits(hosts.size + 1, engines + 1)) {
            return CodeModePoolHost().also(hosts::add)
        }
        if (least == null || least.load() >= CodeModeHeap.maxEnginesPerHost) return null
        return least.takeIf { fits(hosts.size, engines + 1) }
    }

    fun capacity(): CodeModeCapacityException = CodeModeCapacityException(
        "Code-mode pool is full: $memoryBudgetMb MiB head reservation (quirks.code_mode_memory_mb), " +
            "$maxWorkers hosts (quirks.code_mode_workers), ${CodeModeHeap.maxEnginesPerHost} engines per host; " +
            "every retained engine has a live cell",
    )

    private fun fits(hosts: Int, engines: Int): Boolean = reservedBytes(hosts, engines) <= budgetBytes
}
