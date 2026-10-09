// NEW: one daemon-owned native login owner serves both Accounts and managed launch refusal.
package splice.app.auth.claude

import kotlinx.coroutines.CoroutineScope
import splice.client.wrap.WrapStateRead
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRegistry
import splice.topology.TopologyLoader
import splice.upstream.codemode.ProcessDispatchers
import splice.usage.quota.ClientUserAgent
import java.nio.file.Path

/** One daemon's Claude login machinery: the [owner] of each command's own login, and the [accounts] port the
 *  `client` arm serves a head's added subscriptions through. Both are built from the same native client and scope,
 *  so there is one place that knows how a Claude sign-in is started on this machine. */
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
        val processes = ProcessEnvironment()
        val registry = SessionRegistry(
            home.resolve(".claude/sessions"),
            RouteOfPid { pid ->
                processes.route(pid) { port -> topology.heads.entries.firstOrNull { it.value.port == port }?.key }
            },
        )
        val dispatcher = ProcessDispatchers().io()
        val native = NativeClaudeAuth(wrap, mapOf("HOME" to home.toString()), dispatcher)
        val profiles = ClaudeCredentialProfiles(paths.stateDir, log)
        val identities = identityRefresh
            ?: ClaudeIdentityRefresh(scope, dispatcher, profiles, ClaudeProfileProbe(userAgent, log), log)
        // One store for both verbs: the sign-in files an account into it, and the arm's remove deletes from it.
        val folders = ClaudeAccountFolders(paths.stateDir, profileRefresh = identities, changes = poolChanges)
        return ClaudeLoginArm(
            owner = ClaudeLoginOwner(
                ClaudeLoginLocations(home, paths).read(topology),
                ClaudeLoginRead(paths, log, WallClock(System::currentTimeMillis), identities),
                ClaudeLoginSessions(registry),
                native,
                scope,
                poolChanges,
            ),
            accounts = ClaudeAccountsPort(ClaudeAccountSignIn(folders, native, scope), folders),
        )
    }
}
