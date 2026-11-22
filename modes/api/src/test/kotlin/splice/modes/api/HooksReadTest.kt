// NEW: a mode's `[hooks]` read and checked (modes/README.md, "Hooks"): each moment's time limit, what a failure does,
// and whether its handler is in-process or a worker; a key or value the shape does not take is a finding.
package splice.modes.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.capability.TomlTree

class HooksReadTest {
    private fun read(toml: String): Pair<ModeHooksRead, List<String>> {
        val faults = ModeFaults("demo")
        val tree = checkNotNull(TomlTree.read(toml))
        return HooksRead(faults).hooks(tree["hooks"]) to faults.list
    }

    @Test
    fun `every moment reads with its time limit, failure and worker`() {
        val (hooks, faults) = read(
            """
            [hooks]
            order = 10
            [hooks.session_start]
            timeout_s = 5
            on_failure = "closed"
            [hooks.request]
            worker = { kind = "mcp", server = "docs", tool = "annotate" }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), faults)
        assertEquals(10, hooks.order)
        assertEquals(
            listOf(ModeHookMoment.SESSION_START, ModeHookMoment.REQUEST),
            hooks.hooks.map { it.moment },
        )
        val start = hooks.hooks.first { it.moment == ModeHookMoment.SESSION_START }
        assertEquals(5L, start.timeoutS)
        assertTrue(start.failClosed)
        assertNull(start.worker)
        assertEquals(
            ModeHookWorker.Mcp("docs", "annotate"),
            hooks.hooks.first { it.moment == ModeHookMoment.REQUEST }.worker,
        )
    }

    @Test
    fun `an http worker keeps its url and auth env`() {
        val (hooks, faults) = read(
            """
            [hooks]
            [hooks.request]
            worker = { kind = "http", url = "http://127.0.0.1:9/hook", auth_env = "HOOK_KEY" }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), faults)
        assertEquals(
            ModeHookWorker.Http("http://127.0.0.1:9/hook", "HOOK_KEY"),
            hooks.hooks.single().worker,
        )
    }

    @Test
    fun `a moment the manifest does not take, a bad time and a bad failure are findings, all at once`() {
        val (hooks, faults) = read(
            """
            [hooks]
            [hooks.nonesuch]
            [hooks.session_start]
            timeout_s = 0
            on_failure = "sometimes"
            """.trimIndent(),
        )

        assertEquals(emptyList<ModeHook>(), hooks.hooks)
        assertEquals(3, faults.size, faults.joinToString("\n"))
        assertTrue(faults.any { "nonesuch" in it })
        assertTrue(faults.any { "timeout_s" in it })
        assertTrue(faults.any { "on_failure" in it })
    }

    @Test
    fun `a worker with an unknown kind, or a key its kind does not take, is a finding`() {
        val (_, faults) = read(
            """
            [hooks]
            [hooks.request]
            worker = { kind = "carrier-pigeon" }
            [hooks.session_start]
            worker = { kind = "http", url = "http://127.0.0.1:9/hook", server = "docs" }
            """.trimIndent(),
        )

        assertTrue(faults.any { "kind is neither http nor mcp" in it }, faults.joinToString("\n"))
        assertTrue(faults.any { "worker.server is not a key this worker kind takes" in it }, faults.joinToString("\n"))
    }
}
