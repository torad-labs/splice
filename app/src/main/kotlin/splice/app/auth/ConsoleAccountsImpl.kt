package splice.app.auth

import splice.accounts.signin.AccountMutation
import splice.accounts.signin.ConsoleAccounts
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginStart
import splice.accounts.signin.LoginStatus
import splice.app.cli.auth.LoginCommand
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.oauth.OAuthAccountFiles
import splice.oauth.OAuthAccountRefused
import splice.oauth.OAuthPrimaryAccount
import splice.topology.TopologyLoader
import java.nio.file.Path

// why: the console and CLI use OAuthAccountFiles' reserved label for the primary credential file.
private const val PRIMARY_LABEL = "primary"

/** The configured topology used to resolve a head's OAuth account files. */
internal fun interface ConsoleAccountsTopology {
    fun read(): Topology
}

/** Console account operations delegate long-running sign-in to [LoginSessions]. */
internal class ConsoleAccountsImpl(
    private val sessions: LoginSessions = LoginSessions(),
    private val files: OAuthAccountFiles = OAuthAccountFiles(),
    private val first: OAuthPrimaryAccount = OAuthPrimaryAccount(files),
    private val topologies: ConsoleAccountsTopology = ConsoleAccountsTopology {
        TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
    },
) : ConsoleAccounts {
    override suspend fun startLogin(headKey: String, label: String?, restart: HeadRestart): LoginStart {
        val topology = topologies.read()
        val provider = provider(headKey, topology) ?: return LoginStart.UnknownHead
        if (!AuthKindRegistry.isOAuth(provider.auth.kind)) {
            return LoginStart.UnsupportedAuthKind(provider.auth.kind)
        }
        return LoginStart.Started(sessions.start(headKey, provider, topology, label, restart))
    }

    override fun pollLogin(id: String): LoginStatus? = sessions.poll(id)

    override suspend fun cancelLogin(id: String): LoginStatus? = sessions.cancel(id)

    override fun openLogins(): List<LoginStatus> = sessions.open()

    override suspend fun removeAccount(headKey: String, label: String, restart: HeadRestart): AccountMutation {
        // The first account signs in with no label, so no label is the place a sign-in still open for it holds. Under
        // its reserved name the removal looked for a sign-in nobody starts, and the open one could file it again.
        sessions.withdraw(headKey, label.takeUnless { it == PRIMARY_LABEL })
        return removeFiled(headKey, label).also { rediscover(label, it, restart) }
    }

    /** A ChatGPT, Grok, Kimi or Muse head builds its pool once, when it starts, so it went on holding a login whose
     *  file was just removed or renamed: the roster listed it as signed out, with a Sign in that would file it again
     *  under the label just removed, until the daemon next restarted (Marcos, Nov 11, 2026: "I had issues before like
     *  this adding new accounts, removing"). The head starts again and finds its pool as it is on disk, the way it
     *  does when a sign-in lands ([LoginSessions]). A head's first login is read where it is, so it needs none, and
     *  a Claude head never answers Ok here: it follows its folders by itself. A head that does not come back is its
     *  own start's to report: the file is gone either way. */
    private suspend fun rediscover(label: String, answered: AccountMutation, restart: HeadRestart) {
        if (answered != AccountMutation.Ok || label == PRIMARY_LABEL) return
        Cancellables.discard(
            Cancellables.runCatchingBestEffort { restart.restart() },
            "the login is removed or renamed on disk whether or not the head comes back",
        )
    }

    private fun removeFiled(headKey: String, label: String): AccountMutation =
        onOAuthHead(headKey) { target ->
            val removed = if (label == PRIMARY_LABEL) {
                first.remove(target.kind, target.primaryFile)
            } else {
                files.remove(target.kind, target.primaryFile, label)
            }
            if (!removed) throw OAuthAccountRefused("no OAuth account labeled '$label'")
        }

    override suspend fun relabelAccount(
        headKey: String,
        label: String,
        newLabel: String,
        restart: HeadRestart,
    ): AccountMutation =
        onOAuthHead(headKey) { target ->
            if (label == PRIMARY_LABEL) {
                first.rename(target.kind, target.primaryFile, newLabel)
            } else {
                files.relabel(target.kind, target.primaryFile, label, newLabel)
            }
        }.also { rediscover(label, it, restart) }

    /** A configured head with another auth kind keeps its own refusal; it is not an unknown head. */
    private inline fun onOAuthHead(headKey: String, block: (OAuthTarget) -> Unit): AccountMutation {
        val provider = provider(headKey, topologies.read()) ?: return AccountMutation.UnknownHead
        val kind = AuthKindRegistry.from(provider.auth.kind) as? AuthKind.OAuth
            ?: return AccountMutation.UnsupportedAuthKind(provider.auth.kind)
        return mutate { block(OAuthTarget(kind, LoginCommand().oauthAuthPath(provider))) }
    }

    // inline: the two mutation sites share a refusal boundary without allocating a callback port.
    private inline fun mutate(block: () -> Unit): AccountMutation = try {
        block()
        AccountMutation.Ok
    } catch (e: OAuthAccountRefused) {
        AccountMutation.Refused(e.reason)
    }

    private fun provider(headKey: String, topology: Topology): ProviderConfig? {
        val providerKey = topology.heads[headKey]?.provider ?: return null
        return topology.providers[providerKey]
    }
}

/** Reuses the CLI's credential path resolution, including the configured/default-file precedence. */
private data class OAuthTarget(val kind: AuthKind.OAuth, val primaryFile: Path)
