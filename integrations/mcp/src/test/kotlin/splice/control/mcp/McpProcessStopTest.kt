// A hosted child's generation is settled once its root has exited and nothing in its scope can still run, and only
// then may its server start the child again. systemd answers blank for a scope it does not know, both before it
// registers one and after it removes one. On Oct 26 a lookup right after a spawn came back blank 19 times in 30, so a
// contained child was usually captured with its scope unknown. After a crash that scope could not be found again, the
// generation stayed retained for good, and its server refused every later start as "stopping after a transport
// failure".
package splice.control.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpProcessStopTest {
    @Test
    fun `a child that exited after systemd removed its scope is settled, so its server may start again`() {
        val stop = McpProcessStop(McpScopeLookup { "" })
        val generation = exited(stop, "splice-mcp-removed.scope")
        assertEquals(emptyList<McpProcessGeneration>(), stop.stopGenerations(listOf(generation)))
        assertFalse(stop.retains(generation))
    }

    @Test
    fun `a child whose scope systemd could not be asked about stays retained`() {
        val stop = McpProcessStop(McpScopeLookup { null })
        val generation = exited(stop, "splice-mcp-unanswered.scope")
        assertEquals(listOf(generation), stop.stopGenerations(listOf(generation)))
        assertTrue(stop.retains(generation))
    }

    private fun exited(stop: McpProcessStop, unit: String): McpProcessGeneration {
        val child = ProcessBuilder("true").start()
        val generation = stop.capture(child, unit)
        child.waitFor()
        return generation
    }
}
