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
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LruSizing
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.util.UUID

// why: an open add is one operator's form, held until its save or discard; 64 is far above any
// console in use at once, and the map is access-ordered, so the cap only evicts forms nobody touched.
private const val MAX_OPEN_ADDS = 64

/** One console add in progress: the candidate, and what has happened to it since. */
// MUST be a plain class: it is the monitor its sign-in and save synchronize on, and the LRU holds it by
// id. A data class would give structural equality over the mutable fields below, so two forms for the
// same profile would compare equal.
internal class AddSession(val id: String, val profile: String, val candidate: AddCandidate) {
    @Volatile var signInId: String? = null

    @Volatile var checks: List<AddCheck>? = null

    @Volatile var saved: AddSaved? = null

    /** Whether the save already happened, so a second save or sign-in answers "already saved". */
    fun isSaved(): Boolean = saved != null
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

    /** A local runtime has no operator credential or sign-in flow. */
    data object NoKeyRequired : AddSignInOutcome()

    data object AlreadySaved : AddSignInOutcome()
}

internal sealed class AddSaveOutcome {
    data class Saved(val saved: AddSaved) : AddSaveOutcome()

    data class ChecksFailed(val checks: List<AddCheck>) : AddSaveOutcome()

    /** Nothing written: splice.toml changed or vanished since the add opened. */
    data class Stale(val refused: AddWritten.Refused) : AddSaveOutcome()

    data object AlreadySaved : AddSaveOutcome()
}

/** The console add's own monitor: an add's save and an add-model never interleave under it. */
internal class AddConsoleLock

/** [output] takes the lines the add's pieces print (an unreadable credential file, CredentialPresence). */
public class AddConsole(
    private val signIn: AddSignIn,
    install: WrapperInstall,
    private val env: EnvReader,
    private val output: TerminalOutput,
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
    private val writes = AddConsoleLock()
    private val saver = Saver()

    /** `splice add-model` as the console runs it, under the same [writes] lock as a save. */
    internal val models = AddModelConsole(env, writes)
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
    internal fun credential(s: AddSession): AddCheck = checks.credential(s.candidate, env)

    internal fun signInStatus(s: AddSession): LoginStatus? = s.signInId?.let(signIn::poll)

    /** A second request while a flow runs answers that flow rather than starting another. */
    internal fun signIn(s: AddSession): AddSignInOutcome = synchronized(s) {
        val kind = s.candidate.provider.auth.kind
        when {
            s.isSaved() -> AddSignInOutcome.AlreadySaved
            kind == AuthKind.Client.wire -> AddSignInOutcome.NoSignIn
            kind == API_KEY && !s.candidate.resolved.requiresKey -> AddSignInOutcome.NoKeyRequired
            !AuthKindRegistry.isOAuth(kind) -> AddSignInOutcome.ByKey(keyEnv(s))
            else -> AddSignInOutcome.Started(running(s) ?: started(s))
        }
    }

    /** The env var an api-key candidate reads its key from, as its heads will. */
    internal fun keyEnv(s: AddSession): String = s.candidate.provider.auth.effectiveApiKeyEnv(s.candidate.key)

    internal fun verify(s: AddSession, live: Boolean): List<AddCheck> =
        checks.all(s.candidate, live, env).also { s.checks = it }

    /** The checks again, then the write, the wrapper and the restart — in the CLI's order. */
    internal fun save(s: AddSession, restart: AddDaemonRestart): AddSaveOutcome = synchronized(s) {
        if (s.isSaved()) return AddSaveOutcome.AlreadySaved
        val results = verify(s, live = false)
        if (results.any { !it.ok }) return AddSaveOutcome.ChecksFailed(results)
        val local = s.candidate.resolved.authKind == API_KEY && !s.candidate.resolved.requiresKey
        if (local) saver.withLocalPlaceholder(s, results, restart) else saver.finishSave(s, restart)
    }

    /** The write and its optional local-placeholder claim are one operation under AddConsole's
     *  existing session monitor; the nested collaborator keeps KeyStore ownership in one place. */
    private inner class Saver {
        fun finishSave(s: AddSession, restart: AddDaemonRestart): AddSaveOutcome =
            when (val written = synchronized(writes) { AddWrite().write(s.candidate) }) {
                is AddWritten.Refused -> AddSaveOutcome.Stale(written)
                AddWritten.Written -> {
                    val saved = AddSaved(link.link(s.candidate.key, env), restart.take())
                    s.saved = saved
                    AddSaveOutcome.Saved(saved)
                }
            }

        /** Claim before the file can name it; a refused save removes only our own value. */
        fun withLocalPlaceholder(s: AddSession, checks: List<AddCheck>, restart: AddDaemonRestart): AddSaveOutcome {
            val store = KeyStore(KeyStorePath.defaultPath(env))
            val envVar = keyEnv(s)
            val planted = Cancellables.runCatchingCancellable {
                store.placeholders.writeIfAbsent(envVar, RUNTIME_KEY_PLACEHOLDER)
            }.getOrElse { failure ->
                val reason = SafeFailureText.render(failure)
                val failed = checks.map { row ->
                    if (row.name == "credential") {
                        row.copy(ok = false, detail = "could not store local placeholder: $reason")
                    } else {
                        row
                    }
                }
                s.checks = failed
                return AddSaveOutcome.ChecksFailed(failed)
            }
            return try {
                finishSave(s, restart)
            } finally {
                if (planted && !landed(s)) {
                    Cancellables.runCatchingCancellable {
                        store.placeholders.unsetIfValue(envVar, RUNTIME_KEY_PLACEHOLDER)
                    }.onFailure {
                        output.line(
                            "  $envVar placeholder could not be taken back: ${SafeFailureText.render(it)}",
                        )
                    }
                }
            }
        }

        /** An unreadable file may already hold the new head, so keep its placeholder. */
        private fun landed(s: AddSession): Boolean = Cancellables.runCatchingCancellable {
            s.candidate.key in TopologyLoader.parse(Files.readString(s.candidate.path)).providers
        }.fold(onSuccess = { it }, onFailure = { true })
    }

    private fun running(s: AddSession): LoginStatus? =
        signInStatus(s)?.takeIf { it.state == LoginState.STARTING || it.state == LoginState.WAITING }

    private fun started(s: AddSession): LoginStatus {
        val c = s.candidate
        return signIn.start(c.key, c.provider, c.topology).also { s.signInId = it.id }
    }
}
