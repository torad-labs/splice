// NEW: LAYOUT-01 — the regex arm of the code-mode suite, kept in :app because only the fat jar can lose
// a Truffle language registration. codeModePackagedTest reruns it beside :integrations-codemode's
// CodeModeRuntimeTest and CodeModeBridgeRuntimeTest, each with the shipped jar as the worker classpath.
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
