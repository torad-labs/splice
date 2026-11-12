package splice.provider.codex.state.heap

import splice.core.memory.HeapJson
import java.lang.management.ManagementFactory
import javax.management.JMException
import javax.management.ObjectName
import javax.management.openmbean.CompositeData

// why: a String and the JSON primitive that holds it, around the array that stores its characters.
private const val TEXT_OWNER_BYTES = 64L

// why: a byte array's header in front of the characters it stores.
private const val ARRAY_HEADER_BYTES = 16L

// why: every heap object is padded to a multiple of eight bytes.
private const val OBJECT_ALIGNMENT = 8L

// why: the highest code point a compact string stores in one byte.
private const val LATIN1_MAX = 0xFF

/** Weighs retained text using the JVM's compact strings and G1 humongous-array region layout. */
internal object CodeModeStoredText {
    fun bytes(value: String): Long {
        val width = if (CodeModeTextLayout.compactStrings && value.all { it.code <= LATIN1_MAX }) 1L else 2L
        return HeapJson.add(TEXT_OWNER_BYTES, array(HeapJson.add(ARRAY_HEADER_BYTES, value.length * width)))
    }

    private fun array(bytes: Long): Long {
        val aligned = padded(bytes, OBJECT_ALIGNMENT)
        val region = CodeModeTextLayout.regionBytes
        return if (region > 0 && aligned >= region / 2) padded(aligned, region) else aligned
    }

    private fun padded(bytes: Long, unit: Long): Long = HeapJson.add(bytes, unit - 1) / unit * unit
}

/** A JVM without the HotSpot diagnostic bean is weighed at two bytes a character with no regions. */
private object CodeModeTextLayout {
    private val server = ManagementFactory.getPlatformMBeanServer()
    private val diagnostic = ObjectName("com.sun.management:type=HotSpotDiagnostic")
    val compactStrings: Boolean = option("CompactStrings") == "true"
    val regionBytes: Long = if (option("UseG1GC") == "true") option("G1HeapRegionSize")?.toLongOrNull() ?: 0L else 0L

    private fun option(name: String): String? = try {
        val option = server.invoke(diagnostic, "getVMOption", arrayOf(name), arrayOf("java.lang.String"))
        (option as? CompositeData)?.get("value") as? String
    } catch (_: JMException) {
        null
    }
}
