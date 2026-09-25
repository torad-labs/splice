// NEW: V4-220 item 3 (2026-09-25) — `splice add` as the console runs it. The operator asked why the
// console was read only, and adding a backend is the first thing the CLI does that it could not.
//
// NOTHING HERE DECIDES DIFFERENTLY FROM THE CLI. The candidate is AddPrepare's, the checks are
// AddChecks.all, the write is AddWrite (the file re-read, then one rename), and the wrapper link is
// AddWrapperLink: the console only spreads the one terminal session over several requests, so the
// operator can sign in between them. An open add holds its candidate in memory and writes nothing until
// its save; the save runs the checks again first, because a credential or an endpoint can change in the
// minutes a sign-in takes.
//
// THE SIGN-IN IS THE CONSOLE'S EXISTING SEAM (V4-132's LoginSessions, through AddSignIn), started with
// the candidate's provider and topology because the head is in no file yet. An api-key profile signs
// in through the key store (PUT /api/keys/{ENV}, V4-220 item 1), and a client profile forwards the
// operator's own Claude login, so neither starts a flow here.
package splice.configuration.add

import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.util.EnvReader
import splice.core.util.LruSizing
import java.util.UUID

// why: an open add is one operator's form, held until its save or discard; 64 is far above any
// console in use at once, and the map is access-ordered, so the cap only evicts forms nobody touched.
private const val MAX_OPEN_ADDS = 64

/** One console add in progress: the candidate, and what has happened to it since. */
// MUST be a plain class: it is the monitor its sign-in and save synchronize on, and the LRU holds it by
// id. A data class would give structural equality over the mutable fields below, so two forms for the
// same profile would compare equal. UseDataClass is a FALSE POSITIVE here (as InflightGate.Waiter).
@Suppress("UseDataClass")
internal class AddSession(val id: String, val profile: String, val candidate: AddCandidate) {
    @Volatile var signInId: String? = null

    @Volatile var checks: List<AddCheck>? = null

    @Volatile var saved: AddSaved? = null
}

/** A save that happened: the wrapper link's result and the restart the daemon took on. */
internal data class AddSaved(val link: AddLinked, val restart: AddRestartTaken)

internal sealed class AddOpened {
    data class Opened(val session: AddSession) : AddOpened()

    data class Refused(val refusal: AddRefusal, val conflict: Boolean) : AddOpened()

    data object UnknownProfile : AddOpened()
}

/** How the candidate's head signs in. */
internal sealed class AddSignInOutcome {
    data class Started(val status: LoginStatus) : AddSignInOutcome()

    /** An api-key head: its key goes into the key store under [env]. */
    data class ByKey(val env: String) : AddSignInOutcome()

    /** A client head: the operator's own Claude login is forwarded at launch. */
    data object NoSignIn : AddSignInOutcome()

    data object AlreadySaved : AddSignInOutcome()
}

internal sealed class AddSaveOutcome {
    data class Saved(val saved: AddSaved) : AddSaveOutcome()

    data class ChecksFailed(val checks: List<AddCheck>) : AddSaveOutcome()

    /** Nothing written: splice.toml changed or vanished since the add opened. */
    data class Stale(val refused: AddWritten.Refused) : AddSaveOutcome()

    data object AlreadySaved : AddSaveOutcome()
}

/** [output] takes the lines the add's pieces print (an unreadable credential file, CredentialPresence). */
public class AddConsole(
    private val signIn: AddSignIn,
    install: WrapperInstall,
    private val env: EnvReader,
    output: TerminalOutput,
) {
    private val checks = AddChecks(output)

    // No terminal: every question takes its default, so a profile that ships no models refuses
    // ("no models: ...") instead of waiting on a prompt nobody sees.
    private val prepare = AddPrepare(output, checks, AddPrompter { _, default -> default })
    private val link = AddWrapperLink(install)
    private val lock = Any()

    // Two forms open at once share one splice.toml and one temp name (AddWrite's is per process), so one
    // save's re-read and rename never interleave with another's: the second then sees the first's
    // tables and refuses as stale, never renaming over them.
    private val writes = Any()
    private val sessions = object : LinkedHashMap<String, AddSession>(
        LruSizing.INITIAL_CAPACITY,
        LruSizing.LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AddSession>?) = size > MAX_OPEN_ADDS
    }

    internal fun profiles(): List<AddProfile> = AddProfiles().catalog()

    internal fun open(args: AddArgs): AddOpened = when (val prepared = prepare.prepare(args, env)) {
        AddPrepared.UnknownProfile -> AddOpened.UnknownProfile
        is AddPrepared.Refused -> AddOpened.Refused(prepared.refusal, prepared.conflict)
        is AddPrepared.Ready -> {
            val session = AddSession(UUID.randomUUID().toString(), args.profile.orEmpty(), prepared.candidate)
            synchronized(lock) { sessions[session.id] = session }
            AddOpened.Opened(session)
        }
    }

    internal fun session(id: String): AddSession? = synchronized(lock) { sessions[id] }

    internal fun discard(id: String): Boolean = synchronized(lock) { sessions.remove(id) != null }

    /** The credential check, read now: a sign-in or a key set since the last read shows at once. */
    internal fun credential(s: AddSession): AddCheck = checks.credential(s.candidate.key, s.candidate.provider, env)

    internal fun signInStatus(s: AddSession): LoginStatus? = s.signInId?.let(signIn::poll)

    /** A second request while a flow runs answers that flow rather than starting another. */
    internal fun signIn(s: AddSession): AddSignInOutcome = synchronized(s) {
        val kind = s.candidate.provider.auth.kind
        when {
            s.saved != null -> AddSignInOutcome.AlreadySaved
            kind == AuthKind.Client.wire -> AddSignInOutcome.NoSignIn
            !AuthKindRegistry.isOAuth(kind) -> AddSignInOutcome.ByKey(keyEnv(s))
            else -> AddSignInOutcome.Started(running(s) ?: started(s))
        }
    }

    /** The env var an api-key candidate reads its key from, as its heads will. */
    internal fun keyEnv(s: AddSession): String = s.candidate.provider.auth.effectiveApiKeyEnv(s.candidate.key)

    internal fun verify(s: AddSession, live: Boolean): List<AddCheck> =
        checks.all(s.candidate, live, env).also { s.checks = it }

    /** The checks again, then the write, the wrapper and the restart — in the CLI's order, and nothing
     *  after a step that refused. */
    internal fun save(s: AddSession, restart: AddDaemonRestart): AddSaveOutcome = synchronized(s) {
        if (s.saved != null) return AddSaveOutcome.AlreadySaved
        val results = verify(s, live = false)
        val written = if (results.all { it.ok }) synchronized(writes) { AddWrite().write(s.candidate) } else null
        when (written) {
            null -> AddSaveOutcome.ChecksFailed(results)
            is AddWritten.Refused -> AddSaveOutcome.Stale(written)
            AddWritten.Written -> {
                val saved = AddSaved(link.link(s.candidate.key, env), restart.take())
                s.saved = saved
                AddSaveOutcome.Saved(saved)
            }
        }
    }

    private fun running(s: AddSession): LoginStatus? =
        signInStatus(s)?.takeIf { it.state == LoginState.STARTING || it.state == LoginState.WAITING }

    private fun started(s: AddSession): LoginStatus {
        val c = s.candidate
        return signIn.start(c.key, c.provider, c.topology).also { s.signInId = it.id }
    }
}
