// NEW: gateway-no-heads. What a person is told when a provider the gateway serves has no login to run a turn on: the
// words `splice claude --json` and `splice desktop` print, and the cases that earn each of them.
package splice.app.provider

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import java.io.IOException

class ProviderLoginsTest {
    private val logins = ProviderLogins()

    /** A login that describes itself as [description], or fails to when [failure] is set. */
    private class Described(
        private val description: AuthDescription,
        private val failure: Throwable? = null,
    ) : AuthProvider {
        override suspend fun credentials(): Credentials? = null

        override suspend fun describe(): AuthDescription = failure?.let { throw it } ?: description
    }

    private fun read(
        description: AuthDescription,
        forwardsCaller: Boolean = false,
        labels: List<String> = emptyList(),
    ): ProviderLogin = runBlocking {
        logins.of(Described(description), forwardsCaller, labels, signIn = "claude-acme login")
    }

    private fun missing(login: ProviderLogin): String = (login as ProviderLogin.Missing).reason

    @Test
    fun `a provider that forwards the caller's login with no account added says so, and never uses the person's own`() {
        val none = read(AuthDescription(present = true, kind = "client"), forwardsCaller = true)
        val own = read(
            AuthDescription(present = true, kind = "client"),
            forwardsCaller = true,
            labels = listOf(OWN_SIGN_IN_LABEL),
        )
        val native = read(
            AuthDescription(present = true, kind = "client"),
            forwardsCaller = true,
            labels = listOf("native:work"),
        )

        val words = "no Claude account is added to it, and the door never sends your own Claude login; " +
            "add one on splice's accounts page (`splice console`)"
        assertEquals(words, missing(none))
        assertEquals(words, missing(own), "the sign-in a head forwards is the person's own, never an added account")
        assertEquals(words, missing(native), "a login read from the person's own Claude folder is not an added account")
    }

    @Test
    fun `a provider that forwards the caller's login is ready once an account is added to it`() {
        val added = read(
            AuthDescription(present = true, kind = "client"),
            forwardsCaller = true,
            labels = listOf(OWN_SIGN_IN_LABEL, "work"),
        )

        assertEquals(ProviderLogin.Ready, added)
    }

    @Test
    fun `a provider holding its login is ready, and one signed out says how to sign in`() {
        assertEquals(ProviderLogin.Ready, read(AuthDescription(present = true, kind = "chatgpt-oauth")))
        assertEquals(
            "it is signed out; sign in with `claude-acme login`",
            missing(read(AuthDescription(present = false, kind = "chatgpt-oauth"))),
        )
    }

    @Test
    fun `a login whose refresh was refused for good reads as signed out though a token is there`() {
        val expired = AuthDescription(
            present = true,
            kind = "chatgpt-oauth",
            fields = mapOf("refresh_latched" to "true"),
        )

        assertEquals("it is signed out; sign in with `claude-acme login`", missing(read(expired)))
    }

    @Test
    fun `an API key provider with no key says where its key is read from, or how to set one`() {
        val file = AuthDescription(present = false, kind = "api-key", fields = mapOf("key_file" to "/keys/acme"))
        val named = AuthDescription(present = false, kind = "api-key", fields = mapOf("env_var" to "ACME_KEY"))
        val bare = AuthDescription(present = false, kind = "api-key")

        assertEquals("its key file /keys/acme holds no key", missing(read(file)))
        assertEquals(
            "it has no API key; set one with `printf '%s' \"\$KEY\" | splice key set ACME_KEY --stdin`",
            missing(read(named)),
        )
        assertEquals("it has no API key", missing(read(bare)))
        assertEquals(ProviderLogin.Ready, read(AuthDescription(present = true, kind = "api-key")))
    }

    @Test
    fun `a login file that cannot be read says so, with the command that signs it in`() {
        val broken = Described(
            AuthDescription(present = true, kind = "chatgpt-oauth"),
            IOException("disk gone"),
        )

        val login = runBlocking {
            logins.of(broken, forwardsCaller = false, labels = emptyList(), signIn = "claude-acme login")
        }

        val reason = missing(login)
        assertTrue(reason.startsWith("its login could not be read ("), reason)
        assertTrue(reason.endsWith("); sign in with `claude-acme login`"), reason)
    }

    @Test
    fun `a provider is called with a login when it holds a sign-in or has accounts added, not a key`() {
        assertTrue(logins.usesLogin(forwardsCaller = true, authKind = "client"))
        assertTrue(logins.usesLogin(forwardsCaller = false, authKind = "chatgpt-oauth"))
        assertTrue(logins.usesLogin(forwardsCaller = false, authKind = "kimi-oauth"))
        assertFalse(logins.usesLogin(forwardsCaller = false, authKind = "api-key"))
        assertFalse(logins.usesLogin(forwardsCaller = false, authKind = "command"))
    }
}
