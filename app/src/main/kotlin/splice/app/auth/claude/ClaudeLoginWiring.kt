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
import java.nio.file.Path

/** One daemon's Claude login machinery: the [owner] of each command's own login, and the [addAccount] sign-in that
 *  adds a subscription to a head. Both are built from the same native client and scope, so there is one place that
 *  knows how a Claude sign-in is started on this machine. */
internal data class ClaudeLoginArm(val owner: ClaudeLoginOwner, val addAccount: ClaudeAccountSignIn)

internal object ClaudeLoginWiring {
    fun create(
        paths: StatePaths,
        topologyPath: Path?,
        scope: CoroutineScope,
        wrap: WrapStateRead,
        log: LogSink,
    ): ClaudeLoginArm {
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
        return ClaudeLoginArm(
            owner = ClaudeLoginOwner(
                ClaudeLoginLocations(home, paths).read(topology),
                ClaudeLoginRead(paths, log, WallClock(System::currentTimeMillis)),
                ClaudeLoginSessions(registry),
                native,
                scope,
            ),
            addAccount = ClaudeAccountSignIn(ClaudeAccountFolders(paths.stateDir), native, scope),
        )
    }
}
