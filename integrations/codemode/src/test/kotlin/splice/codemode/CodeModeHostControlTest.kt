// NEW: slow opens, lifecycle failures and unseen closes cannot block unrelated sessions or leak native engines.
package splice.codemode

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.upstream.codemode.CodeModeResult
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import splice.upstream.failure.CodeModeInfrastructureException
import java.util.concurrent.TimeUnit

@Timeout(60)
class CodeModeHostControlTest {
    @Test
    fun `another engine opening cannot stop the frame reader from resuming a parked cell`() = runBlocking<Unit> {
        HostControlFixture().use { fixture ->
            fixture.reply(fixture.send(1, HostProtocol.command("session-open")))
            val start = CodeModeWire.startFrame("return await tools.Read({});", setOf("Read"))
            val parked = fixture.reply(fixture.send(1, start))
            assertEquals(1, CodeModeFrames.parseReply(parked, setOf("Read"), 1).calls?.size)
            fixture.factory.blockCreation = 2
            val opening = fixture.send(2, HostProtocol.command("session-open"))
            assertTrue(fixture.factory.entered.await(5, TimeUnit.SECONDS))
            try {
                val results = CodeModeWire.resultFrame(listOf(CodeModeResult("1", "alive")))
                val resumed = fixture.reply(fixture.send(1, results))
                assertEquals("alive", CodeModeFrames.parseReply(resumed, setOf("Read"), 2).output)
            } finally {
                fixture.factory.release.countDown()
            }
            fixture.reply(opening)
        }
    }

    @Test
    fun `an engine open failure gets a prompt bounded infrastructure reply`() = runBlocking<Unit> {
        HostControlFixture(ControlledHostEngines().also { it.failOpen = true }).use { fixture ->
            val reply = fixture.reply(fixture.send(1, HostProtocol.command("session-open")))
            assertFault(reply)
            val counts = fixture.reply(fixture.send(1, HostProtocol.command("engines")))
            assertEquals("0", counts["count"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `an engine close failure gets a prompt bounded infrastructure reply`() = runBlocking<Unit> {
        HostControlFixture().use { fixture ->
            fixture.reply(fixture.send(1, HostProtocol.command("session-open")))
            fixture.factory.failClose.set(true)
            assertFault(fixture.reply(fixture.send(1, HostProtocol.command("session-close"))))
            val retained = fixture.reply(fixture.send(1, HostProtocol.command("engines")))
            assertEquals("1", retained["count"]?.jsonPrimitive?.content)
            fixture.reply(fixture.send(1, HostProtocol.command("session-close")))
            val closed = fixture.reply(fixture.send(1, HostProtocol.command("engines")))
            assertEquals("0", closed["count"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `a close before a delayed open leaves no uncharged engine`() = runBlocking<Unit> {
        HostControlFixture().use { fixture ->
            fixture.reply(fixture.send(1, HostProtocol.command("session-close")))
            fixture.reply(fixture.send(1, HostProtocol.command("session-open")))
            val count = fixture.reply(fixture.send(1, HostProtocol.command("engines")))
            assertEquals("0", count["count"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `two held opens cannot consume another session's close controls`() = runBlocking<Unit> {
        val factory = ControlledHostEngines(heldOpens = 2)
        HostControlFixture(factory).use { fixture ->
            fixture.reply(fixture.send(1, HostProtocol.command("session-open")))
            factory.blockCreations.addAll(listOf(2, 3))
            val second = fixture.send(2, HostProtocol.command("session-open"))
            val third = fixture.send(3, HostProtocol.command("session-open"))
            assertTrue(factory.entered.await(5, TimeUnit.SECONDS))
            try {
                fixture.reply(fixture.send(1, HostProtocol.command("session-close")))
            } finally {
                factory.release.countDown()
            }
            fixture.reply(second)
            fixture.reply(third)
        }
    }

    @Test
    fun `opening and cancelling engines still count against the native host cap`() = runBlocking<Unit> {
        HostControlFixture().use { fixture ->
            fixture.reply(fixture.send(1, HostProtocol.command("session-open")))
            fixture.factory.blockCreation = 2
            val opening = fixture.send(2, HostProtocol.command("session-open"))
            assertTrue(fixture.factory.entered.await(5, TimeUnit.SECONDS))
            val closing = fixture.send(2, HostProtocol.command("session-close"))
            try {
                fixture.reply(fixture.send(3, HostProtocol.command("session-open")))
                fixture.reply(fixture.send(4, HostProtocol.command("session-open")))
                val refused = fixture.reply(fixture.send(5, HostProtocol.command("session-open")))
                assertEquals("capacity", refused["type"]?.jsonPrimitive?.content)
                val detail = refused["detail"]?.jsonPrimitive?.content.orEmpty()
                assertTrue(detail.contains("4 engines"))
                assertTrue(detail.contains("quirks.code_mode_workers"))
                assertTrue(detail.contains("quirks.code_mode_memory_mb"))
            } finally {
                fixture.factory.release.countDown()
            }
            fixture.reply(opening)
            fixture.reply(closing)
            val count = fixture.reply(fixture.send(1, HostProtocol.command("engines")))
            assertEquals("3", count["count"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `duplicate opens of a hung engine cannot occupy its sibling's opening lane`() = runBlocking<Unit> {
        HostControlFixture().use { fixture ->
            fixture.factory.blockCreation = 1
            val opening = fixture.send(1, HostProtocol.command("session-open"))
            assertTrue(fixture.factory.entered.await(5, TimeUnit.SECONDS))
            val duplicates = List(3) { fixture.send(1, HostProtocol.command("session-open")) }
            val sibling = fixture.send(2, HostProtocol.command("session-open"))
            try {
                assertTrue(
                    fixture.factory.siblingEntered.await(5, TimeUnit.SECONDS),
                    "B must enter its factory while A and its three duplicate opens are still held",
                )
                fixture.reply(sibling)
            } finally {
                fixture.factory.release.countDown()
            }
            fixture.reply(opening)
            duplicates.forEach { fixture.reply(it) }
        }
    }

    private fun assertFault(reply: kotlinx.serialization.json.JsonObject) {
        val error = assertThrows(CodeModeInfrastructureException::class.java) { CodeModeFatalFrame.parse(reply) }
        assertEquals(CodeModeInfrastructureCategory.HOST, error.category)
        assertEquals(CodeModeInfrastructureClass.IO, error.faultClass)
        assertFalse(reply.toString().contains("private"))
    }
}
