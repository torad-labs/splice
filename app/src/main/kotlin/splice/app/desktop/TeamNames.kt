// NEW: gateway-no-heads. The names a team's grants call a provider by, each name meaning exactly one provider. A grant
// (`[server] commands`, a person's or team's `--commands`) was written against heads: it names a head by its command or
// its key, and that head runs one provider. The gateway also lets it name a provider by its key. A name that is both a
// head's and another provider's key keeps its legacy meaning, the head's provider, and the other provider is then
// reachable by no grant until the clash is renamed (ServerChecks says so at boot), so a grant is never read as more than
// the one provider it was written for.
package splice.app.desktop

import splice.core.topology.Topology

internal class TeamNames(topology: Topology) {
    private val providerOfName: Map<String, String> = buildMap {
        topology.heads.forEach { (key, head) ->
            putIfAbsent(key, head.provider)
            putIfAbsent(head.claude.command ?: key, head.provider)
        }
        topology.providers.keys.forEach { putIfAbsent(it, it) }
    }

    /** The names a grant may call [provider] by: every name that means it and no other provider. */
    fun of(provider: String): Set<String> =
        providerOfName.filterValues { it == provider }.keys
}
