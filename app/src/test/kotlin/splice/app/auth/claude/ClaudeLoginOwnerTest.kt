// NEW: native replacement holds managed launch, saves actual bytes, and clears every child on cancellation.
package splice.app.auth.claude

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.signin.LoginState
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.client.wrap.WrapStateRead
import splice.core.config.StatePaths
import splice.core.util.WallClock
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class ClaudeLoginOwnerTest {
    @TempDir
    lateinit var home: Path

    private fun location(): ClaudeLoginLocation {
        val folder = Files.createDirectories(home.resolve("native"))
        return ClaudeLoginLocation(
            ClaudeLoginPlaceId.NATIVE,
            ClaudeLoginTarget(ClaudeHead("claude", folder), home.resolve(".claude.json")),
            home.resolve("copies"),
        )
    }

    /** The other command's place, whose head key is NOT the one a fixed string would name. */
    private fun other(): ClaudeLoginLocation {
        val folder = Files.createDirectories(home.resolve("splice-native"))
        return ClaudeLoginLocation(
            ClaudeLoginPlaceId.SPLICE,
            ClaudeLoginTarget(ClaudeHead("claude-splice", folder), home.resolve("splice.claude.json")),
            home.resolve("splice-copies"),
        )
    }

    private fun live(location: ClaudeLoginLocation, account: String, bytes: String) {
        Files.writeString(location.credentials, bytes)
        Files.writeString(location.target.accountFile, """{"oauthAccount":{"accountUuid":"$account"}}""")
    }

    private fun sessions(error: String? = null): ClaudeLoginSessions = ClaudeLoginSessions(object : SessionSource {
        override fun read(): List<SessionRecord> = emptyList()
        override fun list(): SessionListing = SessionListing(emptyList(), error)
    })

    private fun owner(
        location: ClaudeLoginLocation,
        scope: CoroutineScope,
        start: NativeAuthStart,
        sessions: ClaudeLoginSessions = sessions(),
        places: List<ClaudeLoginLocation> = listOf(location),
    ): ClaudeLoginOwner {
        val paths = StatePaths(baseOverride = home.resolve("state"))
        val auth = NativeClaudeAuth(
            WrapStateRead { "fixture-native" },
            emptyMap(),
            ProcessDispatchers().io(),
            start,
        )
        return ClaudeLoginOwner(places, ClaudeLoginRead(paths, {}, WallClock { 1000 }), sessions, auth, scope)
    }

    @Test
    fun `a pending native login refuses duplicate replacement and managed launches then files the fresh account`() =
        runBlocking {
            val location = location()
            live(location, "outgoing", "outgoing rotated bytes")
            val child = NativeLoginTestProcess("https://claude.ai/oauth/authorize?fixture=allowed\n")
            val started = CompletableDeferred<Unit>()
            val launches = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
            val start = NativeAuthStart {
                launches.incrementAndGet()
                started.complete(Unit)
                child
            }
            val owner = owner(location, scope, start)
            try {
                val login = owner.login(ClaudeLoginPlaceId.NATIVE, "fresh")
                withTimeout(5000) { started.await() }
                assertNotNull(owner.refusal(location.target.head.configDir))
                val alias = home.resolve("native-alias")
                Files.createSymbolicLink(alias, location.target.head.configDir)
                assertNotNull(owner.refusal(alias))
                val duplicate = owner.login(ClaudeLoginPlaceId.NATIVE, null)
                assertEquals(LoginState.FAILED, duplicate.state)
                assertEquals(1, launches.get())
                val savedOutgoing = location.storeDir.resolve("account-outgoing.credentials.json")
                assertEquals("outgoing rotated bytes", Files.readString(savedOutgoing))
                live(location, "new-account", "fresh native bytes")
                child.finish(0)
                withTimeout(5000) {
                    while (owner.refusal(location.target.head.configDir) != null) yield()
                }
                assertEquals(LoginState.SIGNED_IN, owner.poll(login.id)?.state)
                assertEquals("fresh", owner.poll(login.id)?.label)
                val savedFresh = location.storeDir.resolve("fresh.credentials.json")
                assertEquals("fresh native bytes", Files.readString(savedFresh))
                assertEquals("fresh native bytes", Files.readString(location.credentials))
                assertNull(owner.poll("foreign-id"))
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }

    @Test
    fun `a sign-in names its own command, so the console shows it on that card and not another's`() = runBlocking {
        val native = location()
        live(native, "outgoing", "outgoing bytes")
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val child = NativeLoginTestProcess()
        val owner = owner(native, scope, NativeAuthStart { child }, places = listOf(native, other()))
        try {
            val login = owner.login(ClaudeLoginPlaceId.NATIVE, null)

            assertEquals("claude", login.head, "the sign-in opened on the claude card belongs to claude")
            assertEquals("claude", owner.poll(login.id)?.head, "and the status the console polls says the same")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `daemon cancellation stops the native child before releasing launch and never restores outgoing bytes`() =
        runBlocking {
            val location = location()
            live(location, "outgoing", "outgoing bytes")
            val child = NativeLoginTestProcess()
            val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
            val owner = owner(location, scope, NativeAuthStart { child })
            val login = owner.login(ClaudeLoginPlaceId.NATIVE, null)
            withTimeout(5000) { child.exitObserved.await() }
            live(location, "partial", "partially replaced bytes")
            scope.coroutineContext[Job]!!.cancelAndJoin()
            assertFalse(child.isAlive)
            assertEquals(LoginState.FAILED, owner.poll(login.id)?.state)
            assertNull(owner.refusal(location.target.head.configDir))
            assertEquals("partially replaced bytes", Files.readString(location.credentials))
            val savedOutgoing = location.storeDir.resolve("account-outgoing.credentials.json")
            assertEquals("outgoing bytes", Files.readString(savedOutgoing))
        }

    @Test
    fun `unreadable sessions refuse before any native process or save-back starts`() = runBlocking {
        val location = location()
        live(location, "outgoing", "outgoing bytes")
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val owner = owner(location, scope, NativeAuthStart { error("must not spawn") }, sessions("fixture unreadable"))
        try {
            val refused = owner.login(ClaudeLoginPlaceId.NATIVE, null)
            assertEquals(LoginState.FAILED, refused.state)
            assertFalse(Files.exists(location.storeDir))
            assertTrue(refused.failureReason.orEmpty().contains("fixture unreadable"))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
