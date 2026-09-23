// NEW: LAYOUT-01 — the shipped jar's half of the code-mode suite. The runtime's own tests live with
// :integrations-codemode; what only :app can check is that the fat jar still carries both Truffle
// languages, so this class and CodeModeBridgeRuntimeTest are what codeModePackagedTest reruns.
package splice.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.codemode.JvmCodeModeRuntime
import splice.upstream.codemode.CodeModeStep

class CodeModeLanguagesTest {
    private val testClasspath: String = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `bundled regex language is available to JavaScript`() = runBlocking {
        JvmCodeModeRuntime(workerClasspath = testClasspath).use { runtime ->
            val step = runtime.start("""return "codes 17 and 4".match(/\d+/g).join("+");""", emptySet()).advance()
            val completed = step as CodeModeStep.Completed
            assertEquals("17+4", completed.output)
            assertNull(completed.error)
        }
    }
}
