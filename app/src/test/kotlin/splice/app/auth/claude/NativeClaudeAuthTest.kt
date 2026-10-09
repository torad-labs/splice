// native auth resolves the executable, destination and bounded browser announcement without live credentials.
package splice.app.auth.claude

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class NativeClaudeAuthTest {
    @TempDir
    lateinit var home: Path

    private fun location(id: ClaudeLoginPlaceId): ClaudeLoginLocation {
        val directory = home.resolve(id.wire)
        return ClaudeLoginLocation(id, ClaudeLoginTarget(ClaudeHead("claude-splice", directory), directory), home)
    }

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
        (auth.begin(location(ClaudeLoginPlaceId.NATIVE)) as NativeSignIn.Running).run.use { }
        (auth.begin(location(ClaudeLoginPlaceId.SPLICE)) as NativeSignIn.Running).run.use { }
        assertEquals(listOf("/synthetic/real-claude", "auth", "login", "--claudeai"), builders[0].command())
        assertFalse(builders[0].environment().containsKey("CLAUDE_CONFIG_DIR"))
        assertEquals(home.resolve("claude-splice").toString(), builders[1].environment()["CLAUDE_CONFIG_DIR"])
        for (builder in builders) {
            assertFalse(builder.environment().containsKey("ANTHROPIC_AUTH_TOKEN"))
            assertFalse(builder.environment().containsKey("CLAUDE_CODE_OAUTH_TOKEN"))
            assertFalse(builder.environment().containsKey("CLAUDE_CODE_USE_BEDROCK"))
            assertEquals("http://proxy.invalid", builder.environment()["HTTPS_PROXY"])
        }
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
        (auth.begin(location(ClaudeLoginPlaceId.NATIVE)) as NativeSignIn.Running).run.use { child ->
            assertTrue(child.await { announced += it })
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
            (auth.begin(location(ClaudeLoginPlaceId.NATIVE)) as NativeSignIn.Running).run.use { child ->
                assertTrue(child.await { announced += it })
            }
            assertEquals(listOf(good), announced.toList())
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
        (auth.begin(location(ClaudeLoginPlaceId.NATIVE)) as NativeSignIn.Running).run.use { child ->
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
