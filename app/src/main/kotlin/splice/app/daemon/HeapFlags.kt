// NEW: Oct 19, 2026 — the heap discipline splice-launch passes as flags, applied by the daemon itself, so a daemon
// started any other way (a plain `java -jar splice.jar daemon`, a user's own unit, SPLICE_JVM_OPTS, which replaces the
// launcher's defaults) still hands back the heap it stops using: splice keeps its own memory in check on any host,
// with no host tool required (Marcos, Oct 19, 2026: the fix needs to exist in Splice).
package splice.app.daemon

import splice.core.util.LogSink
import java.lang.management.ManagementFactory
import javax.management.JMException
import javax.management.JMRuntimeException
import javax.management.MBeanServer
import javax.management.ObjectName
import javax.management.openmbean.CompositeData

// why: the launcher's free ratios (splice-launch): G1 keeps at least 20% and at most 40% of the committed heap free
// after a collection that resizes it, instead of 40% and 70%, so committed follows live data down.
private const val MIN_HEAP_FREE_PERCENT = "20"
private const val MAX_HEAP_FREE_PERCENT = "40"

// why: the launcher's periodic collection: a daemon idle for a minute runs a concurrent cycle, which returns the heap.
private const val PERIODIC_GC_MS = "60000"

// The HotSpot diagnostic bean, reached by its platform name.
private const val HOTSPOT_DIAGNOSTIC = "com.sun.management:type=HotSpotDiagnostic"

// The JVM's origin for a flag nobody set.
private const val DEFAULT_ORIGIN = "DEFAULT"

// The bean's operations take strings: their JMX signature names the type.
private const val STRING_TYPE = "java.lang.String"

/** Sets the launcher's manageable heap flags at boot, each only where the person starting the JVM left it at its
 *  default: a value they chose is theirs. A flag the JVM refuses is logged and skipped, never a boot failure. */
internal class HeapFlags(private val log: LogSink) {
    private val server: MBeanServer = ManagementFactory.getPlatformMBeanServer()
    private val diagnostic = ObjectName(HOTSPOT_DIAGNOSTIC)

    fun apply() {
        // Min before max: the JVM refuses a max under the current min, and the default min (40) is not over our max.
        listOf(
            "MinHeapFreeRatio" to MIN_HEAP_FREE_PERCENT,
            "MaxHeapFreeRatio" to MAX_HEAP_FREE_PERCENT,
            "G1PeriodicGCInterval" to PERIODIC_GC_MS,
        ).forEach { (name, value) -> set(name, value) }
    }

    private fun set(name: String, value: String) {
        try {
            val option = server.invoke(diagnostic, "getVMOption", arrayOf(name), arrayOf(STRING_TYPE))
                as? CompositeData ?: return
            val was = option.get("value")?.toString()
            if (option.get("origin")?.toString() != DEFAULT_ORIGIN || was == value) return
            server.invoke(diagnostic, "setVMOption", arrayOf(name, value), arrayOf(STRING_TYPE, STRING_TYPE))
            log("heap: $name=$value (was $was)\n")
        } catch (error: JMException) {
            log("heap: $name not set: ${error.message}\n")
        } catch (error: JMRuntimeException) {
            log("heap: $name not set: ${error.message}\n")
        }
    }
}
