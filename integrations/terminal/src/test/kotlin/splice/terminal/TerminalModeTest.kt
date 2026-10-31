// NEW: CW-1 — TerminalMode through an injected runner. Restore on the exception path;
// zero stty when there is no console.
package splice.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalModeTest {

    @Test
    fun `non-TTY runs the block with zero stty calls`() {
        val stty = RecordingStty()
        val mode = TerminalMode(
            stty = stty,
            hasConsole = { false },
            addHook = { error("hook must not register off-TTY") },
            removeHook = { error("hook must not remove off-TTY") },
        )
        assertEquals(7, mode.raw { 7 })
        assertTrue(stty.calls.isEmpty(), "non-TTY must not invoke stty")
    }

    @Test
    fun `TTY captures, enters raw, restores, and deregisters the hook`() {
        val stty = RecordingStty()
        val hooks = HookLog()
        val mode = TerminalMode(
            stty = stty,
            hasConsole = { true },
            addHook = hooks::add,
            removeHook = hooks::remove,
        )
        assertEquals("ok", mode.raw { "ok" })
        assertEquals(
            listOf(
                listOf("stty", "-g"),
                listOf("stty", "-icanon", "-echo", "min", "1", "time", "0"),
                listOf("stty", "SAVED"),
            ),
            stty.calls,
        )
        assertEquals(1, hooks.added)
        assertEquals(1, hooks.removed)
    }

    @Test
    fun `restore fires on the exception path`() {
        val stty = RecordingStty()
        val hooks = HookLog()
        val mode = TerminalMode(
            stty = stty,
            hasConsole = { true },
            addHook = hooks::add,
            removeHook = hooks::remove,
        )
        assertThrows(IllegalStateException::class.java) {
            mode.raw<Unit> { throw IllegalStateException("boom") }
        }
        assertEquals(listOf("stty", "SAVED"), stty.calls.last())
        assertEquals(1, hooks.added)
        assertEquals(1, hooks.removed)
    }

    @Test
    fun `failed stty dash g runs the block unraw and never enters raw`() {
        val stty = FailingCaptureStty()
        val hooks = HookLog()
        val mode = TerminalMode(
            stty = stty,
            hasConsole = { true },
            addHook = hooks::add,
            removeHook = hooks::remove,
        )
        assertEquals(11, mode.raw { 11 })
        assertEquals(listOf(listOf("stty", "-g")), stty.calls)
        assertEquals(0, hooks.added)
        assertEquals(0, hooks.removed)
    }

    private class RecordingStty : SttyCommand {
        val calls = mutableListOf<List<String>>()
        override fun run(args: List<String>): SttyResult {
            calls.add(args)
            return if (args.getOrNull(1) == "-g") SttyResult(0, "SAVED") else SttyResult(0, "")
        }
    }

    private class FailingCaptureStty : SttyCommand {
        val calls = mutableListOf<List<String>>()
        override fun run(args: List<String>): SttyResult {
            calls.add(args)
            return SttyResult(1, "")
        }
    }

    private class HookLog {
        var added = 0
        var removed = 0
        fun add(hook: Thread) {
            added += 1
            check(hook.threadId() >= 0)
        }
        fun remove(hook: Thread) {
            removed += 1
            check(hook.threadId() >= 0)
        }
    }
}
