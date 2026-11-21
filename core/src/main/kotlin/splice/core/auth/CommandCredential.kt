// NEW: custom-provider (issue #680, cp-credential). A provider on `auth = { kind = "command", credential_command }`
// gets its key from the team's own command, as Claude Code's apiKeyHelper does for Anthropic and awsAuthRefresh for
// Bedrock: a short-lived personal key renews by itself and nobody pastes keys.
//
// When it runs: when no key is held, when the held key expires within five minutes (the window the ChatGPT sign-in
// renews in), and once after the provider answers 401 (the retry engine's refused-login path, exchange), one run at a
// time per provider. The key is held in this object's memory alone: never in a file, a log line, splice's state, the
// environment of any process splice starts (the command runs with the daemon's own environment, to which nothing is
// added), or an answer of the control plane; describe() names its state and expiry, never the key.
package splice.core.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.topology.COMMAND_AUTH_WIRE
import splice.core.util.WallClock
import java.time.Instant

/** What a provider's command credential is now: [Held] (with its expiry, when it has one), [None] (never run) or
 *  [Failed]. Read by `/api/auth`, `splice claude`, `splice doctor` and the accounts page; it never carries the key.
 *  [fields] is the one mapping of a state onto the wire those answers share. */
public sealed class CommandCredentialState {
    /** `state`, and `expires_at` or `error` when this state has one. The words are [COMMAND_STATE_HELD],
     *  [COMMAND_STATE_NONE] and [COMMAND_STATE_FAILED], written here and nowhere else. */
    public fun fields(): Map<String, String> = when (this) {
        is Held -> buildMap {
            put(COMMAND_STATE_FIELD, COMMAND_STATE_HELD)
            expiresAt?.let { put(COMMAND_EXPIRES_FIELD, it.toString()) }
        }
        None -> mapOf(COMMAND_STATE_FIELD to COMMAND_STATE_NONE)
        is Failed -> mapOf(COMMAND_STATE_FIELD to COMMAND_STATE_FAILED, ERROR_FIELD to error)
    }

    public data class Held(val expiresAt: Instant?) : CommandCredentialState()

    public data object None : CommandCredentialState()

    /** [error] is what doctor and `splice claude` show. [said] is false when the command printed nothing and [error]
     *  is splice's own words (`exited <code>`, a timeout, no credential): the turn then says the person is not signed
     *  in, not those words. */
    public data class Failed(val error: String, val said: Boolean) : CommandCredentialState()
}

/** What one run of a provider's credential command produced. */
public sealed class CredentialRunResult {
    public class Key(public val token: String, public val expiresAt: Instant?) : CredentialRunResult() {
        override fun toString(): String = "Key(token=<redacted>, expiresAt=$expiresAt)"
    }

    /** [words] is what a failed run is shown as: the command's stderr, or splice's own words when it printed nothing.
     *  [said] is false in that second case, so a turn is not refused in splice's words. */
    public data class Failed(val words: String, val said: Boolean) : CredentialRunResult()
}

/** One run of a provider's credential command. */
public fun interface CredentialRun {
    public suspend operator fun invoke(): CredentialRunResult
}

/** One provider's credential, from [run] (CredentialCommand over its `credential_command`). Shared by every head of
 *  the provider. */
public class CommandCredential(
    private val run: CredentialRun,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : RefreshableAuthProvider {
    private val lock = Mutex()

    @Volatile private var held: HeldKey? = null

    @Volatile private var last: CommandCredentialState = CommandCredentialState.None

    /** What the credential is now, for every surface that names it. */
    public val state: CommandCredentialState get() = last

    override suspend fun credentials(): Credentials? = usable()?.let { Credentials.ApiKey(it.token) }

    /** No key to be had is said in the command's words (or that the person is not signed in), so the turn's refusal
     *  says why, not only that. */
    override suspend fun answer(): LoginAnswer =
        credentials()?.let(LoginAnswer::Granted) ?: LoginAnswer.Unavailable(why())

    override suspend fun refresh(): Credentials? = lock.withLock { runLocked() }?.let { Credentials.ApiKey(it.token) }

    /** After a 401: a key another turn already renewed since [refused] was sent is adopted; else the command runs. */
    override suspend fun exchange(refused: Credentials?): RefreshOutcome = lock.withLock {
        val current = held
        val sent = (refused as? Credentials.ApiKey)?.key
        val adopted = current?.takeIf { sent != null && it.token != sent && fresh(it) }
        val renewed = adopted ?: runLocked()
        renewed?.let { RefreshOutcome.Refreshed(Credentials.ApiKey(it.token)) } ?: RefreshOutcome.Rejected(why())
    }

    override suspend fun exchange(): RefreshOutcome = exchange(null)

    /** A sign-in that just finished: the held key is dropped and the command runs once more now. */
    public suspend fun renewNow(): CommandCredentialState {
        lock.withLock {
            held = null
            runLocked()
        }
        return last
    }

    override suspend fun describe(): AuthDescription = AuthDescription(
        present = held?.let(::fresh) == true,
        kind = COMMAND_KIND,
        fields = last.fields(),
    )

    private suspend fun usable(): HeldKey? {
        held?.takeIf(::fresh)?.let { return it }
        return lock.withLock { held?.takeIf(::fresh) ?: runLocked() }
    }

    private fun fresh(key: HeldKey): Boolean {
        val expires = key.expiresAt ?: return true
        return expires.toEpochMilli() - clock() > RENEW_BEFORE_MS
    }

    private suspend fun runLocked(): HeldKey? = when (val ran = run()) {
        is CredentialRunResult.Key -> HeldKey(ran.token, ran.expiresAt).also {
            held = it
            last = CommandCredentialState.Held(ran.expiresAt)
        }
        is CredentialRunResult.Failed -> {
            held = null
            last = CommandCredentialState.Failed(ran.words, ran.said)
            null
        }
    }

    /** The command's own words, or that the person is not signed in when the command printed nothing. Splice's own
     *  `exited <code>` stays on the failed state for doctor and `splice claude`; it is not what a turn is told. */
    private fun why(): String {
        val failed = last as? CommandCredentialState.Failed ?: return NOT_SIGNED_IN
        return failed.error.takeIf { failed.said && it.isNotBlank() } ?: NOT_SIGNED_IN
    }

    /** The key itself, kept out of every rendering. */
    private data class HeldKey(val token: String, val expiresAt: Instant?) {
        override fun toString(): String = "HeldKey(token=<redacted>, expiresAt=$expiresAt)"
    }
}

/** The `state` field [CommandCredential.describe] names its state by, and the three words that field carries. */
public const val COMMAND_STATE_FIELD: String = "state"
public const val COMMAND_STATE_HELD: String = "held"
public const val COMMAND_STATE_NONE: String = "none"
public const val COMMAND_STATE_FAILED: String = "failed"

private const val COMMAND_KIND = COMMAND_AUTH_WIRE

/** The field the expiry rides under, and the key a credential command prints it by. */
internal const val COMMAND_EXPIRES_FIELD: String = "expires_at"
private const val ERROR_FIELD = "error"

/** Why a turn has no key when the command failed and said nothing. */
private const val NOT_SIGNED_IN = "you are not signed in"

// why: the window the ChatGPT sign-in renews in (CodexAuthProvider's proactive window): a key this close to its expiry
// could lapse mid-turn, so it is renewed before the turn is sent.
private const val RENEW_BEFORE_MS = 5L * 60 * 1000
