// NEW: synthetic identity census rejects quadratic retained recovery echoes without inspecting operator data.
package splice.provider.codex.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.LogSink
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.state.CodeModeNativeChain
import splice.upstream.RoundBody
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

private const val RECOVERY_SEGMENT_CHARS = 1024

class CodeModeRecoveryRetentionTest {
    private val wire = CodexCodeModeWire(Json, LogSink { })

    @Test
    fun `one recovery followed by many scripts retains only linear emitted text`() {
        val samples = listOf(100, 200).map { scripts ->
            val history = history()
            var echo = recoveryText()
            repeat(scripts) { index ->
                val outcome = success(index)
                val record = CodeModeRecords.of("synthetic", index)
                history.remember(
                    record,
                    checkNotNull(wire.anchoredBoundary(baseline(), emptyList())),
                    CodeModeNativeChain.Capture(emptyList(), null),
                    wire.continuity(outcome),
                    outcome.text.bodyText,
                )
                echo += outcome.text.bodyText
                assertEquals(echo, checkNotNull(history.delivery(record)).text(null))
                history.generated(outcome)
            }
            val retained = retainedText(history)
            println("RECOVERY_RETAINED scripts=$scripts emitted_chars=${echo.length} retained_chars=$retained")
            Triple(scripts, echo.length, retained)
        }
        samples.forEach { (scripts, emitted, retained) ->
            assertTrue(retained <= emitted * 3L, "$scripts scripts retain $retained chars for $emitted emitted chars")
        }
        assertTrue(samples.last().third <= samples.first().third * 3L, "doubling scripts must not quadruple storage")
    }

    @Test
    fun `older delivery snapshots keep their exact echo after later scripts append`() {
        val history = history()
        val records = (0 until 32).map { CodeModeRecords.of("synthetic", it) }
        val boundary = checkNotNull(wire.anchoredBoundary(baseline(), emptyList()))
        var echo = recoveryText()
        val echoes = records.mapIndexed { index, record ->
            val outcome = success(index)
            history.remember(
                record,
                boundary,
                CodeModeNativeChain.Capture(emptyList(), null),
                wire.continuity(outcome),
                outcome.text.bodyText,
            )
            echo += outcome.text.bodyText
            history.generated(outcome)
            echo
        }
        records.forEachIndexed { index, record ->
            val delivered = checkNotNull(checkNotNull(history.delivery(record)).text(null))
            assertArrayEquals(echoes[index].toByteArray(), delivered.toByteArray())
            val upstream = history.upstream(listOf(record)).single()
            val expected = wire.continuity(success(index))
            assertEquals(expected.logicalItems, upstream.carry.continuity)
            assertEquals(expected.replayItems, upstream.carry.replay)
            assertEquals(boundary.fullDigest, upstream.origin.baseline.inputDigest)
            assertEquals(boundary.logicalDigest, upstream.origin.baseline.logicalDigest)
        }
    }

    private fun history(): CodeModeRecoveryHistory = CodeModeRecoveryHistory(baseline()).also {
        it.extend(TurnOutcome.PartialRound(text = RoundText(bodyText = recoveryText(), emittedText = true)))
    }

    private fun baseline() = wire.body(
        RoundBody.Tree(buildJsonObject { put("input", JsonArray(emptyList())) }),
    )

    private fun recoveryText(): String = "recovered:" + "r".repeat(RECOVERY_SEGMENT_CHARS)

    private fun success(index: Int) = TurnOutcome.Success(
        hasToolUse = false,
        incomplete = false,
        usage = Usage(),
        text = RoundText(
            bodyText = "script-$index:".padEnd(RECOVERY_SEGMENT_CHARS, 'a' + index % 26),
            emittedText = true,
        ),
    )

    /** Follow actual retained entry fields, not a hand-authored list of the strings the implementation should keep. */
    private fun retainedText(history: CodeModeRecoveryHistory): Long {
        val posted = history.javaClass.getDeclaredField("posted").also { it.isAccessible = true }.get(history)
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>().also { it.addLast(posted) }
        var chars = 0L
        while (pending.isNotEmpty()) {
            val value = pending.removeLast()
            if (!visited.add(value)) continue
            if (value is String) chars += value.length else pending.addAll(retainedChildren(value))
        }
        return chars
    }

    private fun <V : Any> retainedChildren(value: V): List<Any> = when (value) {
        is JsonPrimitive -> listOf(value.content)
        is Map<*, *> -> (value.keys + value.values).filterNotNull()
        is Iterable<*> -> value.filterNotNull()
        else -> retainedFields(value)
    }

    private fun <V : Any> retainedFields(value: V): List<Any> {
        if (!value.javaClass.name.startsWith("splice.provider.codex.")) return emptyList()
        return value.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.mapNotNull {
            it.isAccessible = true
            it.get(value)
        }
    }
}
