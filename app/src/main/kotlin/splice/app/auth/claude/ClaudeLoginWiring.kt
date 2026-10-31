// NEW: one daemon-owned native login owner serves both Accounts and managed launch refusal.
package splice.app.auth.claude

import kotlinx.coroutines.CoroutineScope
import splice.client.wrap.WrapStateRead
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.topology.TopologyLoader
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
import splice.usage.quota.ClientUserAgent
import java.nio.file.Path

/** One daemon's Claude login machinery: the [owner] of each command's own login, and the [accounts] port the
 *  `client` arm serves a head's added subscriptions through. Both sign in through the same [ClaudeAccountSignIn], so
 *  there is one place that knows how a Claude sign-in is started on this machine, and every one of them adds. */
internal data class ClaudeLoginArm(val owner: ClaudeLoginOwner, val accounts: ClaudeAccountsPort)

internal class ClaudeLoginWiring(
    private val paths: StatePaths,
    private val scope: CoroutineScope,
    private val wrap: WrapStateRead,
    private val log: LogSink,
    private val userAgent: ClientUserAgent = ClientUserAgent { null },
    private val identityRefresh: ClaudeIdentityRefresh? = null,
    private val poolChanges: ClaudePoolChanges? = null,
) {
    fun create(topologyPath: Path?): ClaudeLoginArm {
        val home = paths.rootDir.parent ?: paths.rootDir
        val topology = topologyPath?.let(TopologyLoader::loadOrMaterialize) ?: Topology()
        val places = ClaudeLoginLocations(home, paths).read(topology)
        val dispatcher = ProcessDispatchers().io()
        val native = NativeClaudeAuth(wrap, mapOf("HOME" to home.toString()), dispatcher)
        val profiles = ClaudeCredentialProfiles(paths.stateDir, log)
        val identities = identityRefresh
            ?: ClaudeIdentityRefresh(scope, dispatcher, profiles, ClaudeProfileProbe(userAgent, log), log)
        // A command's own login holds its account while it can send for it, or while its command renews it on its
        // next run. Signing in the account of a login that is signed out adds that account, so the person has it again.
        val held = ClaudeHeldLogins { head, account ->
            places.firstOrNull { place ->
                place.target.head.key == head &&
                    ClaudeNativeAuth(place.target.head.configDir, place.id, profiles, log).let { auth ->
                        val evidence = auth.credentialEvidence()
                        val sends = evidence.presence == CredentialPresence.PRESENT || evidence.renewable
                        sends && auth.credentialAccount() == account
                    }
            }?.id?.wire
        }
        // One store for both verbs: the sign-in files an account into it, and the arm's remove deletes from it.
        val folders = ClaudeAccountFolders(
            paths.stateDir,
            profileRefresh = identities,
            changes = poolChanges,
            held = held,
        )
        val signIn = ClaudeAccountSignIn(folders, native, scope)
        return ClaudeLoginArm(
            owner = ClaudeLoginOwner(
                places,
                ClaudeLoginRead(paths, log, WallClock(System::currentTimeMillis), identities),
                signIn,
                poolChanges,
            ),
            accounts = ClaudeAccountsPort(signIn, folders),
        )
    }
}
