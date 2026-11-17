// NEW: server mode on a laptop (docs/specs/server-mode.md, F2). `splice login <server>` signs this laptop's person in
// to a team's splice server with the company's identity provider: the server's open `GET /server/signin` says which
// provider and client, the browser opens the provider's sign-in (authorization code with PKCE, back to a loopback
// listener on whatever port is free, the URL always printed too, for a browser on another machine), and the code goes
// to the server, which alone holds the client secret, checks the ID token and mints this device a key. The key is kept
// in an owner-only file of its own (ServerLogins), which the apiKeyHelper of every command on that server prints, so a
// sign-in again hands a running session its new key the next time Claude Code asks. `splice logout <server>` forgets
// the key. `splice login <server> --key-stdin` (F7) keeps a key the server's operator issued in that same file, once
// the server answers its profile to it, and says whose it is.
package splice.app.cli.server

import splice.app.cli.peer.PeerExit
import splice.core.terminal.CYAN
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.oauth.BrowserOpener
import splice.oauth.OAuthLoginFlow
import splice.oauth.SystemBrowserOpener
import splice.share.Asked
import splice.share.LaptopLogins
import splice.share.ProfileKey
import splice.share.ServerProfiles
import splice.share.ServerSignedIn
import splice.share.ServerSignins
import splice.share.ShareMachine
import splice.upstream.codemode.ProcessDispatchers

internal class ServerLogin(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    env: EnvReader = EnvReader(System::getenv),
    private val browser: BrowserOpener = SystemBrowserOpener(out),
) {
    private val logins = LaptopLogins(env).logins
    private val signins = ServerSignins(ProcessDispatchers().io())
    private val profiles = ServerProfiles(ProcessDispatchers().io())

    /** Whether [word], the argument of `splice login` or `logout`, names a server rather than a head. */
    fun names(word: String): Boolean = word.startsWith("http://") || word.startsWith("https://")

    suspend fun login(server: String): Int {
        val origin = logins.origin(server) ?: return refused(notServer(server), LOGIN)
        val device = device(origin)
        val signedIn = when (val read = signIn(origin, device)) {
            is Asked.Refused -> return refused(read.why, LOGIN)
            is Asked.Had -> read.value
        }
        logins.signedIn(origin, device, signedIn)
        val until = signedIn.expiresAt?.let { ", until ${it.substringBefore('T')}" }.orEmpty()
        out.line("$GREEN✓$RESET signed in to $origin as ${signedIn.person} on this device ($device)$until")
        out.line("  its commands: ${CYAN}splice remote add $origin/<command>$RESET")
        return PeerExit.DONE
    }

    /** `splice login <server> --key-stdin` (F7): keeps [given], a key the server's operator issued, once the server
     *  answers its profile to it, in the same owner-only file a sign-in keeps its key in. A key the server refuses is
     *  said in the server's words and none of it is kept. */
    suspend fun keep(server: String, given: String): Int {
        val origin = logins.origin(server) ?: return refused(notServer(server), LOGIN)
        val key = given.trim()
        val holder = when (val read = holder(origin, key)) {
            is Asked.Refused -> return refused(read.why, LOGIN)
            is Asked.Had -> read.value
        }
        val device = device(origin)
        logins.signedIn(origin, device, ServerSignedIn(holder.person, key, holder.prefix, holder.expiresAt))
        val until = holder.expiresAt?.let { ", until ${it.substringBefore('T')}" }.orEmpty()
        out.line("$GREEN✓$RESET kept ${holder.person}'s key ${holder.prefix} for $origin on this laptop$until")
        out.line("  its commands: ${CYAN}splice remote add $origin$RESET")
        return PeerExit.DONE
    }

    /** Whose [key] is, as [origin]'s profile answers it; or why it is no key that server accepts. */
    private suspend fun holder(origin: String, key: String): Asked<ProfileKey> {
        if (key.isEmpty()) return Asked.Refused("no key arrived on stdin; pipe in the one the server's operator issued")
        return when (val read = profiles.read(origin, key)) {
            is Asked.Refused -> Asked.Refused("${read.why}; nothing of the key was kept")
            is Asked.Had -> Asked.Had(read.value.key)
        }
    }

    /** The key [origin] mints [device] once its person signs in at the identity provider the server names. */
    private suspend fun signIn(origin: String, device: String): Asked<ServerSignedIn> {
        val config = when (val read = signins.config(origin)) {
            is Asked.Refused -> return read
            is Asked.Had -> read.value
        }
        val authorization = ProviderAuthorization(origin, config)
        val code = OAuthLoginFlow(out, PrintingBrowser(out, browser)).code(port = 0, signInAt = authorization::at)
            ?: return Asked.Refused("the identity provider sent no code back, so $origin minted no key")
        return signins.trade(origin, authorization.trade(code, device))
    }

    fun logout(server: String): Int {
        val origin = logins.origin(server) ?: return refused(notServer(server), LOGOUT)
        val person = logins.person(origin)
        if (!logins.forget(origin)) {
            out.line("not signed in to $origin on this laptop")
            return PeerExit.DONE
        }
        val who = person?.let { " ($it)" }.orEmpty()
        out.line("$GREEN✓$RESET signed out of $origin$who: this laptop holds no key for it now")
        return PeerExit.DONE
    }

    /** The name this laptop goes by on [origin]. */
    private fun device(origin: String): String =
        logins.device(origin, ShareMachine(LogSink { err.line(it.trimEnd()) }).name())

    private fun notServer(word: String): String = "$word is not a server address, as in $EXAMPLE_SERVER"

    private fun refused(why: String, verb: String): Int {
        err.line("splice $verb: $why")
        return PeerExit.REFUSED
    }
}

private const val EXAMPLE_SERVER = "https://ai.team.example"
private const val LOGIN = "login"
private const val LOGOUT = "logout"
