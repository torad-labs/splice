package splice.codemode.engine

import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.codemode.WorkerStart
import splice.upstream.codemode.CodeModeResult

class WorkerSessionResultPartsTest {
    @Test
    fun `a fast race result dispatches its dependent call while the slow callback remains pending`() {
        Engine.newBuilder("js")
            .option("engine.SpawnIsolate", "true")
            .option("engine.IsolateOption.MaxHeapSize", "256m")
            .build().use { engine ->
                WorkerSession(engine).use { session ->
                    assertThrows(IllegalArgumentException::class.java) {
                        session.advance(listOf(CodeModeResult("1", "unsolicited")))
                    }
                    val first = session.start(
                        WorkerStart(
                            "const winner = await Promise.race([" +
                                "tools.call('Read', {kind: 'fast'}), " +
                                "tools.call('Read', {kind: 'slow'})]); " +
                                "const follow = await tools.call('Read', {kind: winner}); " +
                                "return winner + '|' + follow;",
                            setOf("Read"),
                        ),
                    )
                    assertEquals(listOf("1", "2"), first.calls?.map { it.id })

                    val follow = session.advance(listOf(CodeModeResult("1", "fast")))
                    assertEquals(listOf("3"), follow.calls?.map { it.id })
                    assertNull(follow.output)

                    assertThrows(IllegalArgumentException::class.java) {
                        session.advance(listOf(CodeModeResult("1", "duplicate")))
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        session.advance(listOf(CodeModeResult("4", "unknown")))
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        session.advance(emptyList())
                    }

                    val raced = session.advance(listOf(CodeModeResult("3", "next")))
                    assertNull(raced.calls)
                    assertTrue(raced.waitingForResults)
                    assertEquals("fast|next", raced.output)

                    val drained = session.advance(listOf(CodeModeResult("2", "slow")))
                    assertNull(drained.calls)
                    assertFalse(drained.waitingForResults)
                    assertEquals("fast|next", drained.output)
                }
            }
    }
}
