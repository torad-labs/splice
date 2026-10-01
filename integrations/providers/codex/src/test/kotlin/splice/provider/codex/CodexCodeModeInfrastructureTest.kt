package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.FailureCause
import splice.core.turn.TurnOutcome
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeStartException

class CodexCodeModeInfrastructureTest : CodeModeBridgeTestSupport() {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `startup infrastructure remains retryable but a running failure never reruns its source`(
        atStartup: Boolean,
    ) = runTest {
        var starts = 0
        var closed = false
        val runtime = object : CodeModeRuntime {
            override suspend fun start(
                source: String,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell {
                starts++
                if (atStartup) throw CodeModeStartException(fatal())
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
        if (atStartup) {
            assertEquals(FailureCause.INTERNAL, (result as TurnOutcome.Failure).cause)
            assertFalse(result.deterministic)
            assertTrue(logLines.any { "PROTOCOL/IO" in it })
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
                error("startup retry must reuse the persisted source")
            }
            assertEquals("STARTING", stateFiles.records().single().getValue("phase").jsonPrimitive.content)
            assertEquals(2, starts)
            assertEquals(1, posts)
            assertFalse(closed)
            return@runTest
        }
        assertTrue((result as TurnOutcome.Failure).message.contains("PROTOCOL/IO"))
        val record = stateFiles.records().single()
        assertEquals("LOST", record.getValue("phase").jsonPrimitive.content)
        var retryPost = ""
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) { body ->
            posts++
            retryPost = body
            outerOutcome()
        }
        // The retry of the same request cannot resume a lost cell: it completes the record with
        // the fault as its visible output and goes upstream once; the source is never rerun.
        assertEquals(2, posts)
        assertEquals(1, starts)
        assertTrue(retryPost.contains("PROTOCOL/IO"), retryPost)
        assertTrue(retryPost.contains("sourceRerun"), retryPost)
        assertEquals(!atStartup, closed)
    }

    private fun fatal() = CodeModeInfrastructureException(
        CodeModeInfrastructureCategory.PROTOCOL,
        CodeModeInfrastructureClass.IO,
    )
}
