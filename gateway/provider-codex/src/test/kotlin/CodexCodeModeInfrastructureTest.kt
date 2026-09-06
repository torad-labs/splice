import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeCell
import splice.spi.CodeModeInfrastructureCategory
import splice.spi.CodeModeInfrastructureClass
import splice.spi.CodeModeInfrastructureException
import splice.spi.CodeModeResult
import splice.spi.CodeModeRuntime
import splice.spi.CodeModeStep
import java.nio.file.Files

class CodexCodeModeInfrastructureTest : CodeModeBridgeTestSupport() {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `fatal infrastructure is lost with safe diagnostics and never hidden completion`(atStartup: Boolean) = runTest {
        var starts = 0
        var closed = false
        val runtime = object : CodeModeRuntime {
            override suspend fun start(source: String, tools: Set<String>): CodeModeCell {
                starts++
                if (atStartup) throw fatal()
                return object : CodeModeCell {
                    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = throw fatal()
                    override fun close() { closed = true }
                }
            }
            override fun close() = Unit
        }
        val manager = bridge(runtime)
        var posts = 0
        val result = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            posts++
            outerOutcome()
        }
        assertTrue(result is TurnOutcome.Failure)
        assertTrue((result as TurnOutcome.Failure).message.contains("PROTOCOL/IO"))
        val record = Json.parseToJsonElement(Files.readString(tempDir.resolve("bridge.json"))).jsonObject
            .getValue("records").jsonArray.single().jsonObject
        assertEquals("LOST", record.getValue("phase").jsonPrimitive.content)
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            posts++
            outerOutcome()
        }
        assertEquals(1, posts)
        assertEquals(1, starts)
        assertEquals(!atStartup, closed)
    }

    private fun fatal() = CodeModeInfrastructureException(
        CodeModeInfrastructureCategory.PROTOCOL,
        CodeModeInfrastructureClass.IO,
    )
}
