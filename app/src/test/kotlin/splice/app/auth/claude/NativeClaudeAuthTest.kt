// native auth resolves the executable, destination and bounded browser announcement without live credentials.
package splice.app.auth.claude

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class NativeClaudeAuthTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `the real executable authenticates the requested folder without inherited auth selectors`() {
        val builders = mutableListOf<ProcessBuilder>()
        val environment = mapOf(
            "CLAUDE_CONFIG_DIR" to "/other",
            "ANTHROPIC_AUTH_TOKEN" to "synthetic-ambient",
            "ANTHROPIC_BASE_URL" to "https://wrong.invalid",
            "CLAUDE_CODE_OAUTH_TOKEN" to "synthetic-shadow",
            "CLAUDE_CODE_USE_BEDROCK" to "1",
            "HTTPS_PROXY" to "http://proxy.invalid",
        )
        val auth = NativeClaudeAuth(
            WrapStateRead { ClaudeToRun.Wrapped("/synthetic/real-claude") },
            environment,
            ProcessDispatchers().io(),
            NativeAuthStart {
                builders += it
                NativeLoginTestProcess()
            },
        )
        val pending = home.resolve("pending")
        (auth.begin(pending) as NativeSignIn.Running).run.use { }
        val builder = builders.single()
        assertEquals(listOf("/synthetic/real-claude", "auth", "login", "--claudeai"), builder.command())
        assertEquals(pending.toString(), builder.environment()["CLAUDE_CONFIG_DIR"])
        assertFalse(builder.environment().containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertFalse(builder.environment().containsKey("CLAUDE_CODE_OAUTH_TOKEN"))
        assertFalse(builder.environment().containsKey("CLAUDE_CODE_USE_BEDROCK"))
        assertEquals("http://proxy.invalid", builder.environment()["HTTPS_PROXY"])
    }

    @Test
    fun `only native authorization URLs survive huge unrelated child output and multiple URLs`() = runBlocking {
        val good = "https://claude.ai/oauth/authorize?fixture=allowed"
        val text = "x".repeat(40_000) + " https://evil.invalid/oauth/authorize?private=discarded " + good
        val process = NativeLoginTestProcess(text)
        process.finish(0)
        val announced = CopyOnWriteArrayList<String>()
        val auth = NativeClaudeAuth(
            WrapStateRead { ClaudeToRun.Wrapped("unused-fixture") },
            emptyMap(),
            ProcessDispatchers().io(),
            NativeAuthStart { process },
        )
        (auth.begin(home.resolve("pending")) as NativeSignIn.Running).run.use { child ->
            assertTrue(child.await { announced += it.url })
        }
        assertEquals(listOf(good), announced.toList())
    }

    /** Claude Code 2.1.289 announces its sign-in at claude.com under /cai (a scratch `claude auth login --claudeai`
     *  printed "If the browser didn't open, visit: https://claude.com/cai/oauth/authorize?…"). The allowlist read only
     *  claude.ai and /oauth/authorize, so Accounts never showed the link. Lookalikes stay out. */
    @Test
    fun `the claude dot com sign-in link that Claude Code 2_1_289 prints is announced, and lookalikes are not`() =
        runBlocking {
            val good = "https://claude.com/cai/oauth/authorize?code=true&client_id=fixture"
            val text = "Opening browser to sign in…\nIf the browser didn't open, visit: $good\n" +
                "https://evil.claude.com.invalid/cai/oauth/authorize?x=1 https://claude.com/cai/oauth/other?x=1\n" +
                "Paste code here if prompted > "
            val process = NativeLoginTestProcess(text)
            process.finish(0)
            val announced = CopyOnWriteArrayList<String>()
            val auth = NativeClaudeAuth(
                WrapStateRead { ClaudeToRun.Wrapped("unused-fixture") },
                emptyMap(),
                ProcessDispatchers().io(),
                NativeAuthStart { process },
            )
            (auth.begin(home.resolve("pending")) as NativeSignIn.Running).run.use { child ->
                assertTrue(child.await { announced += it.url })
            }
            assertEquals(listOf(good), announced.toList())
        }

    /** Nov 17, 2026: a Claude sign-in asked for a code only sometimes. Claude Code opens a link that returns to its own
     *  listener and finishes by itself, and prints another whose page ends in a code; the console showed the printed
     *  one. The link Claude Code hands BROWSER is the one shown now, with no code, and the printed one is not. */
    @Test
    fun `the link Claude Code opens is shown and asks for no code, and the printed code link is not shown`() =
        runBlocking {
            val opened = "https://claude.com/cai/oauth/authorize?code=true&client_id=fixture" +
                "&redirect_uri=http%3A%2F%2Flocalhost%3A38947%2Fcallback&state=fixture"
            val printed = "https://claude.com/cai/oauth/authorize?code=true&client_id=fixture" +
                "&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback&state=fixture"
            val said = "Opening browser to sign in…\nIf the browser didn't open, visit: $printed\n"
            val process = NativeLoginTestProcess(said)
            val announced = CopyOnWriteArrayList<NativeAuthLink>()
            val auth = NativeClaudeAuth(
                WrapStateRead { ClaudeToRun.Wrapped("unused-fixture") },
                emptyMap(),
                ProcessDispatchers().io(),
                NativeAuthStart { builder ->
                    // What Claude Code does with BROWSER: runs it with the link, through the real program splice laid.
                    val browser = builder.environment().getValue("BROWSER")
                    assertEquals(0, ProcessBuilder(browser, opened).start().waitFor())
                    process
                },
            )
            (auth.begin(home.resolve("pending")) as NativeSignIn.Running).run.use { child ->
                val waiting = launch { child.await { announced += it } }
                withTimeout(5_000) { while (announced.isEmpty()) yield() }
                process.finish(0)
                waiting.join()
            }
            assertEquals(listOf(NativeAuthLink(opened, pasteBack = false)), announced.toList())
        }

    @Test
    fun `a Claude Code that opens no link leaves the printed one, whose page ends in a code`() = runBlocking {
        val printed = "https://claude.com/cai/oauth/authorize?code=true&client_id=fixture" +
            "&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"
        val process = NativeLoginTestProcess("If the browser didn't open, visit: $printed\n")
        process.finish(0)
        val announced = CopyOnWriteArrayList<NativeAuthLink>()
        val auth = NativeClaudeAuth(
            WrapStateRead { ClaudeToRun.Wrapped("unused-fixture") },
            emptyMap(),
            ProcessDispatchers().io(),
            NativeAuthStart { process },
        )
        (auth.begin(home.resolve("pending")) as NativeSignIn.Running).run.use { child ->
            assertTrue(child.await { announced += it })
        }
        assertEquals(listOf(NativeAuthLink(printed, pasteBack = true)), announced.toList())
    }

    @Test
    fun `open stdin accepts one fallback code and cancellation stops the child before returning`() = runBlocking {
        val process = NativeLoginTestProcess()
        val auth = NativeClaudeAuth(
            WrapStateRead { ClaudeToRun.Wrapped("unused-fixture") },
            emptyMap(),
            ProcessDispatchers().io(),
            NativeAuthStart { process },
        )
        (auth.begin(home.resolve("pending")) as NativeSignIn.Running).run.use { child ->
            assertTrue(child.submit("synthetic-code"))
            assertFalse(child.submit("two\nlines"))
            assertEquals("synthetic-code\n", process.stdin.toString(Charsets.UTF_8))
            val pending = launch { child.await { } }
            withTimeout(5000) { process.exitObserved.await() }
            pending.cancelAndJoin()
            assertFalse(process.isAlive)
        }
    }
}
