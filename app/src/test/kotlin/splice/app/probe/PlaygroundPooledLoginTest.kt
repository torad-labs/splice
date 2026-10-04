// NEW: failover within one provider (splice-lead's 6:06 PM CT followup) — on a command with two or more logins, the
// Playground sends as the login a real turn would use next, never the head's default credential. The pool's own order
// decides it, so a login held on its plan or placed later in Accounts is skipped exactly as a turn skips it, and the
// login's own headers ride on top of the provider's. The echoed request names the login it went as.
package splice.app.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.WiredAccount
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.diagnostics.playground.PlaygroundHead
import splice.diagnostics.playground.PlaygroundResult
import splice.upstream.CredentialHeaders
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown
import java.nio.file.Path

class PlaygroundPooledLoginTest {

    private class Login(private val token: String, private val account: String) : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer(token, accountId = account)
        override suspend fun refresh(): Credentials = credentials()
        override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
    }

    @Test
    fun `a pooled command's Playground send goes as the login the next turn would use`(@TempDir root: Path) = runTest {
        val one = Login("one-token", "one-account")
        val two = Login("two-token", "two-account")
        val logins = listOf(
            WiredAccount("one", true, one, root.resolve("one.json")),
            WiredAccount(
                "two",
                false,
                two,
                root.resolve("two.json"),
                extraHeaders = CredentialHeaders { mapOf("x-synthetic-login" to "two") },
            ),
        )
        val pool = AccountPool(
            logins.map { login ->
                PoolAccount(
                    label = login.label,
                    primary = login.primary,
                    auth = login.auth,
                    quota = AccountQuotaSource { null },
                    cooldown = RateLimitCooldown(ElapsedClock { 0L }),
                    extraHeaders = login.extraHeaders,
                )
            },
            WallClock { System.currentTimeMillis() },
        ).also { it.order = listOf("two", "one") }
        val registry = registered(root).also { it.logins("claudex", pool, logins) }
        var sent: HttpRequestData? = null
        val engine = MockEngine { request ->
            sent = request
            respond(content = "{}", status = HttpStatusCode.OK)
        }

        val probe = UpstreamPlaygroundProbe(registry, HttpClient(engine))

        val outcome = probe.run(PlaygroundHead("claudex", one), "hi", null)

        val request = requireNotNull(sent)
        assertEquals("Bearer two-token", request.headers["Authorization"], "the next turn's login, not the default")
        val provider = requireNotNull(registry["claudex"])
        provider.extraHeaders(two.credentials()).forEach { (name, value) ->
            assertEquals(value, request.headers[name], "the provider's own header $name for that login")
        }
        assertEquals("two", request.headers["x-synthetic-login"], "the login's own headers ride on top")
        val echoed = (outcome as PlaygroundResult).request.jsonObject
        assertEquals("two", echoed["account"]?.jsonPrimitive?.content)
    }
}
