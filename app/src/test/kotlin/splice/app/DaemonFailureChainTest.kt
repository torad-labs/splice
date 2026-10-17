// NEW: fatal coroutine wrappers retain sanitized cause and suppressed throw locations.
package splice.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path

class DaemonFailureChainTest {
    @Test
    fun `an internal coroutine wrapper retains cause and suppressed locations without messages`(@TempDir tmp: Path) {
        val cause = IllegalStateException("synthetic private source bytes").apply {
            stackTrace = arrayOf(StackTraceElement("synthetic.SourceOwner", "advance", "SourceOwner.kt", 41))
            addSuppressed(
                IllegalArgumentException("synthetic private cleanup bytes").apply {
                    stackTrace = arrayOf(StackTraceElement("synthetic.Cleanup", "abort", "Cleanup.kt", 52))
                },
            )
        }
        // The coroutine error is internal to Kotlin but public on the JVM. Exercise the real wrapper without opt-ins.
        val wrapper = Class.forName("kotlinx.coroutines.CoroutinesInternalError")
            .getConstructor(String::class.java, Throwable::class.java)
            .newInstance("synthetic private wrapper bytes", cause) as Throwable
        wrapper.stackTrace = arrayOf(StackTraceElement("synthetic.Dispatcher", "dispatch", "Dispatcher.kt", 63))
        val content = render(tmp, wrapper)
        assertTrue(content.contains("synthetic.Dispatcher.dispatch(Dispatcher.kt:63)"), content)
        assertTrue(content.contains("synthetic.SourceOwner.advance(SourceOwner.kt:41)"), content)
        assertTrue(content.contains("caused by: failure (message withheld"), content)
        assertTrue(content.contains("synthetic.Cleanup.abort(Cleanup.kt:52)"), content)
        assertTrue(content.contains("suppressed: failure (message withheld"), content)
        assertFalse(content.contains("synthetic private"), content)
    }

    @Test
    @Timeout(5)
    fun `cause cycles and a wide suppressed graph have bounded synchronous output`(@TempDir tmp: Path) {
        val first = IllegalStateException("synthetic private first")
        val second = IllegalStateException("synthetic private second")
        first.initCause(second)
        second.initCause(first)
        repeat(100) { first.addSuppressed(IllegalArgumentException("synthetic private suppressed $it")) }
        val content = render(tmp, first)
        assertTrue(content.contains("caused by: failure (message withheld"), content)
        assertTrue(content.length < 30_000, "fatal rendering must not flood the synchronous log")
        assertFalse(content.contains("synthetic private"), content)
    }

    private fun render(tmp: Path, failure: Throwable): String {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        DaemonBoundary().bootFailureHandler(paths).uncaughtException(Thread.currentThread(), failure)
        return Files.readString(paths.logsDir.resolve("daemon.log"))
    }
}
