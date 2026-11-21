// NEW: custom-provider (issue #680, cp-login). A provider's `login_command` is how a person signs in to it, as Claude
// Code's awsAuthRefresh is for Bedrock: an interactive sign-in such as the team CLI's browser or device OAuth. The key
// itself is the credential command's (CommandCredential). Two places run it:
//   - `splice login <head>`: attached to the person's terminal, so its prompts and the address it prints are seen;
//     then the daemon runs the credential command (POST /api/auth/<head>/refresh) and says until when.
//   - the control plane's sign-in (POST /api/auth/<head>/login, the accounts page's Sign in): in the daemon, stdin
//     closed, its first http(s) address shown as the sign-in's browser_url the moment it is printed, then the
//     credential command runs in the daemon.
// Both run with /bin/sh -c and the environment of the process that runs them, to which splice adds nothing it holds.
package splice.app.cli.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.app.cli.AdminSupport
import splice.app.provider.COMMAND_SHELL
import splice.app.provider.CredentialCommands
import splice.app.provider.NO_INPUT
import splice.core.auth.COMMAND_NOT_SIGNED_IN
import splice.core.auth.CommandCredentialState
import splice.core.auth.CommandCredentials
import splice.core.terminal.TerminalOutput
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LocalTimeText
import splice.core.util.ProcessTree
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyRead
import splice.oauth.LoginAnnouncement
import splice.oauth.LoginObserver
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One provider's sign-in by its `login_command`. [head]: the head it was asked for; [provider]: its provider's key. */
internal class CommandSignIn(
    private val head: String,
    private val provider: String,
    private val config: ProviderConfig,
    private val io: CoroutineDispatcher,
    private val output: TerminalOutput = TerminalOutput { println(it) },
) {
    private val command: String? get() = config.auth.loginCommand?.takeIf(String::isNotBlank)

    /** `splice login <head>`: true when the person is signed in at the end. */
    suspend fun inTerminal(env: EnvReader = EnvReader(System::getenv)): LoginCommand.LoginResult {
        val login = command ?: return refused(NO_LOGIN_COMMAND)
        val exit = withContext(io) {
            ProcessBuilder(COMMAND_SHELL, "-c", login).inheritIO().start().waitFor()
        }
        if (exit != 0) return refused("login_command exited $exit")
        return when (val read = renewedByDaemon(env)) {
            is Renewed.SignedIn -> {
                output.line(signedIn(read.expiresAt))
                LoginCommand.LoginResult(true)
            }
            is Renewed.NotSignedIn -> refused(read.why)
        }
    }

    /** The control plane's sign-in: [observer] hears the first address the command prints. A failure's words are
     *  the result's refusal, which the sign-in reports as its failure_reason. */
    suspend fun inDaemon(observer: LoginObserver?): LoginCommand.LoginResult {
        val login = command ?: return LoginCommand.LoginResult(false, refusal = NO_LOGIN_COMMAND)
        // interruptible: a cancelled sign-in interrupts the wait, and the command is stopped with it
        val ran = runInterruptible(io) { detached(login, observer) }
        val credential = config.auth.credentialCommand
        return when {
            ran.exit != 0 -> LoginCommand.LoginResult(false, refusal = ran.why(), unfinished = true)
            credential == null -> LoginCommand.LoginResult(false, refusal = NO_CREDENTIAL_COMMAND)
            else -> renewed(credential)
        }
    }

    /** The key [credential] mints now the person has signed in, or why there is none. */
    private suspend fun renewed(credential: String): LoginCommand.LoginResult =
        when (val state = CommandCredentials.of(provider, credential, CredentialCommands(io)).renewNow()) {
            is CommandCredentialState.Held -> LoginCommand.LoginResult(true)
            is CommandCredentialState.Failed -> LoginCommand.LoginResult(
                false,
                refusal = state.error,
                unfinished = true,
            )
            CommandCredentialState.None ->
                LoginCommand.LoginResult(false, refusal = COMMAND_NOT_SIGNED_IN, unfinished = true)
        }

    /** What a run in the daemon did: its exit (or [LOGIN_TIMED_OUT]) and the last line it wrote on stderr. */
    private data class Ran(val exit: Int, val lastError: String?) {
        /** Why a run that did not exit 0 signed nobody in, in the words its stderr ended on. */
        fun why(): String = if (exit == LOGIN_TIMED_OUT) {
            TIMED_OUT_WORDS
        } else {
            "login_command exited $exit" + lastError?.let { ": $it" }.orEmpty()
        }
    }

    private fun detached(login: String, observer: LoginObserver?): Ran {
        val process = ProcessBuilder(COMMAND_SHELL, "-c", login).redirectInput(File(NO_INPUT)).start()
        val heard = Heard(observer)
        val out = CompletableFuture.runAsync { process.inputReader().forEachLine { heard.line(it, stderr = false) } }
        val err = CompletableFuture.runAsync { process.errorReader().forEachLine { heard.line(it, stderr = true) } }
        try {
            if (!process.waitFor(LOGIN_TIMEOUT_MIN, TimeUnit.MINUTES)) {
                ProcessTree(process).kill()
                return Ran(LOGIN_TIMED_OUT, heard.lastError.get())
            }
            CompletableFuture.allOf(out, err).get(READ_DRAIN_S, TimeUnit.SECONDS)
            return Ran(process.exitValue(), heard.lastError.get())
        } finally {
            // A cancelled sign-in (DELETE, or a newer one) stops its command too.
            if (process.isAlive) ProcessTree(process).kill()
        }
    }

    /** What a run in the daemon printed: the first address, told to [observer] once, and its last stderr line. */
    private class Heard(private val observer: LoginObserver?) {
        private val announced = AtomicReference<String?>(null)
        val lastError = AtomicReference<String?>(null)

        fun line(line: String, stderr: Boolean) {
            if (stderr && line.isNotBlank()) lastError.set(line.trim())
            val url = ADDRESS.find(line)?.value ?: return
            if (announced.compareAndSet(null, url)) observer?.announced(LoginAnnouncement(browserUrl = url))
        }
    }

    private sealed class Renewed {
        data class SignedIn(val expiresAt: Instant?) : Renewed()

        data class NotSignedIn(val why: String) : Renewed()
    }

    /** The daemon runs the credential command now, then says whether it holds a key and until when. */
    private fun renewedByDaemon(env: EnvReader): Renewed {
        val key = (AdminSupport.readMgmtKey(env) as? MgmtKeyRead.Present)?.key
            ?: return Renewed.NotSignedIn(NO_DAEMON)
        val base = "http://127.0.0.1:${AdminSupport.controlPort(env)}/api/auth"
        val reply = ControlPlaneClient.send("$base/$head/refresh", "POST", key, RENEW_TIMEOUT_MS)
            ?.let { ControlPlaneClient.send(base, "GET", key) }
            ?: return Renewed.NotSignedIn(DAEMON_NO_ANSWER)
        return stateOf(reply.body)
    }

    /** What the daemon's /api/auth [body] says of this head: signed in until when, or why not. */
    private fun stateOf(body: String): Renewed {
        val auth = Cancellables.runCatchingCancellable { Json.parseToJsonElement(body) as? JsonObject }
            .fold(onSuccess = { it?.get(head) as? JsonObject }, onFailure = { null })
            ?: return Renewed.NotSignedIn("splice's daemon does not know $head")
        if ((auth["present"] as? JsonPrimitive)?.booleanOrNull == true) {
            val expires = JsonScalars.str(auth, "expires_at")?.let { at ->
                Cancellables.runCatchingCancellable { Instant.parse(at) }.fold(onSuccess = { it }, onFailure = { null })
            }
            return Renewed.SignedIn(expires)
        }
        return Renewed.NotSignedIn(JsonScalars.str(auth, "error")?.takeIf(String::isNotBlank) ?: COMMAND_NOT_SIGNED_IN)
    }

    private fun signedIn(expiresAt: Instant?): String {
        val until = expiresAt?.let { " until ${LocalTimeText().at(it.epochSecond)}" }.orEmpty()
        return "Signed in to $provider$until"
    }

    private fun refused(why: String): LoginCommand.LoginResult {
        output.line("splice login $head: $provider is not signed in: $why")
        return LoginCommand.LoginResult(false, refusal = why)
    }
}

private const val NO_LOGIN_COMMAND =
    "its provider names no login_command, so splice cannot sign you in; sign in with your team's own tool"
private const val NO_CREDENTIAL_COMMAND = "its provider names no credential_command"
private const val TIMED_OUT_WORDS = "login_command timed out after 10 minutes"
private const val NO_DAEMON = "splice's daemon is not running, so it holds no key; start it and run this again"
private const val DAEMON_NO_ANSWER = "splice's daemon did not answer"

/** The first http(s) address a line holds. */
private val ADDRESS = Regex("""https?://[^\s"'<>]+""")

// why: a browser or device sign-in a person has not finished in ten minutes was abandoned; the item decided the bound.
private const val LOGIN_TIMEOUT_MIN = 10L

// why: no command exits with it, so it tells a run that was ended at the bound from every exit the command chose.
private const val LOGIN_TIMED_OUT = -1

// why: once the command exits its pipes close; this only bounds a child that kept them open.
private const val READ_DRAIN_S = 5L

// why: the credential command itself is bounded at 30 seconds (CredentialCommand); the reply waits a little longer.
private const val RENEW_TIMEOUT_MS = 40_000
