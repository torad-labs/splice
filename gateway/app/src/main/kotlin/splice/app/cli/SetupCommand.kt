// NEW: `splice setup` — the guided flow from a fresh install to a configured wrapper.
// Materializes the supported API-key starter and installs its commands. If an operator has explicitly
// added experimental OAuth heads, it walks those heads and offers browser login.
// :app is wall-exempt for println.

package splice.app.cli

import splice.app.TopologyLoader
import splice.core.topology.AuthKind
import splice.core.topology.Topology

private const val BOLD = "\u001B[1m"
private const val DIM = "\u001B[2m"
private const val GREEN = "\u001B[32m"
private const val CYAN = "\u001B[36m"
private const val RESET = "\u001B[0m"

private data class PendingOAuthHead(val key: String, val command: String)

internal suspend fun setup(): Boolean {
    println("${BOLD}splice setup$RESET $DIM— wrap Claude Code with your own model backends$RESET")
    println("$DIM  The starter uses the supported OpenRouter API-key route (OPENROUTER_API_KEY).$RESET")
    println()

    // 1. topology + wrapper commands (+ the `splice` command itself)
    init()
    if (!install("--all")) return false
    installSelf()
    println()

    // 2. sign in to each OAuth head that isn't authed yet
    val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
    val ok = signInPendingHeads(pendingOAuthHeads(topology))

    // 3. next steps
    println()
    println("${BOLD}You're set.$RESET")
    val commands = topology.heads.map { (k, h) -> h.claude.command ?: k }
    println("  Launch      ${commands.joinToString("$DIM · $RESET") { "$CYAN$it$RESET" }}")
    println("  Dashboard   ${CYAN}splice dashboard$RESET")
    println("  Status      ${CYAN}splice status$RESET")
    return ok
}

private fun pendingOAuthHeads(topology: Topology): List<PendingOAuthHead> =
    topology.heads.entries.mapNotNull { (key, head) ->
        val provider = topology.providers[head.provider] ?: return@mapNotNull null
        if (AuthKind.isOAuth(provider.auth.kind) && !authPresent(provider.auth.file, provider.auth.kind)) {
            PendingOAuthHead(key, head.claude.command ?: key)
        } else {
            null
        }
    }

private suspend fun signInPendingHeads(pending: List<PendingOAuthHead>): Boolean {
    if (pending.isEmpty()) {
        println("$GREEN✓$RESET wrapper installed. Set OPENROUTER_API_KEY before launching.")
        return true
    }
    println(
        "$DIM  Experimental OAuth heads are configured explicitly; " +
            "these routes are not vendor-documented.$RESET",
    )
    var ok = true
    for ((key, command) in pending) {
        if (AdminSupport.confirm("Sign in to $CYAN$command$RESET now?", default = true)) {
            if (!login(key)) ok = false
        } else {
            println("  ${DIM}skipped — sign in later with: $command login$RESET")
        }
    }
    return ok
}

private fun authPresent(file: String?, kind: String): Boolean {
    val path = file ?: AuthKind.defaultAuthFileFor(kind) ?: return false
    return AdminSupport.authPresent(path)
}
