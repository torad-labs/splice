// NEW: slow opens, lifecycle failures and unseen closes cannot block unrelated sessions or leak native engines.
package splice.codemode

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.wire.CodeModeFrames
import splice.upstream.codemode.CodeModeResult
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
}
