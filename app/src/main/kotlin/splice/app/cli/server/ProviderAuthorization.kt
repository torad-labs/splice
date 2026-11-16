// NEW: server mode on a laptop (docs/specs/server-mode.md, F2). The browser's half of `splice login <server>`: one
// sign-in's authorization at the identity provider the server names, authorization code with PKCE (S256) back to a
// loopback listener, with a state the callback must carry and a nonce the ID token must carry; and the browser that
// opens it, which prints the address too, for a person on SSH or one who signs in in another browser. What the code is
// traded for, at the server, is ServerLogin's.
package splice.app.cli.server

import splice.core.auth.Pkce
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.FormEncoding
import splice.core.util.SafeFailureText
import splice.oauth.BrowserOpener
import splice.oauth.BrowserSignIn
import splice.oauth.LoopbackCallback
import splice.share.ServerSigninConfig
import splice.share.SigninTrade
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The browser a server sign-in opens, which also prints the address it opens. A browser that does not open leaves
 *  the printed address. */
internal class PrintingBrowser(private val out: TerminalOutput, private val system: BrowserOpener) : BrowserOpener {
    override fun open(url: String): Boolean {
        out.line("splice: if no browser opened, open this address to sign in:")
        out.line(url)
        Cancellables.runCatchingBestEffort { system.open(url) }
            .onFailure { out.line("splice: no browser opened here (${SafeFailureText.render(it)})") }
        return true
    }
}

/** One sign-in at the provider [config] names, for [server]: its PKCE pair, state and nonce, made once. */
internal class ProviderAuthorization(
    private val server: String,
    private val config: ServerSigninConfig,
    private val random: SecureRandom = SecureRandom(),
) {
    private val base64 = Base64.getUrlEncoder().withoutPadding()
    private val pkce = pkce()
    private val state = secret()
    private val nonce = secret()

    @Volatile
    private var redirect: String? = null

    /** The browser's sign-in once the loopback listener holds [port]: the provider's address, sending the code back
     *  to that port with this sign-in's state. */
    fun at(port: Int): BrowserSignIn {
        val back = "http://$REDIRECT_HOST:$port$CALLBACK_PATH"
        redirect = back
        return BrowserSignIn(URI.create(server).authority, url(back), LoopbackCallback(port, CALLBACK_PATH, state))
    }

    /** What the server is sent to trade [code] for [device]'s key: the verifier and nonce this sign-in asked with,
     *  and the redirect the code came back to. */
    fun trade(code: String, device: String): SigninTrade {
        val back = checkNotNull(redirect) { "no code comes back before the browser opens" }
        return SigninTrade(code, pkce.verifier, back, nonce, device)
    }

    /** The provider's authorization address: a code, with PKCE, back to [redirect]. */
    private fun url(redirect: String): String {
        val query = FormEncoding.formEncode(
            "response_type" to "code",
            "client_id" to config.clientId,
            "redirect_uri" to redirect,
            "scope" to config.scopes.joinToString(" "),
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
        )
        val joiner = if ('?' in config.authorizationEndpoint) "&" else "?"
        return "${config.authorizationEndpoint}$joiner$query"
    }

    /** 32 random bytes, base64url: a state or a nonce. */
    private fun secret(): String = base64.encodeToString(ByteArray(RANDOM_BYTES).also(random::nextBytes))

    /** A PKCE pair: a [secret] as the verifier (RFC 7636 asks for 43 to 128 characters) and its S256 challenge. */
    private fun pkce(): Pkce {
        val verifier = secret()
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, base64.encodeToString(digest))
    }
}

private const val REDIRECT_HOST = "127.0.0.1"
private const val CALLBACK_PATH = "/callback"

// why: 32 bytes is the verifier RFC 7636 recommends (43 base64url characters) and 256 bits for a state or nonce.
private const val RANDOM_BYTES = 32
