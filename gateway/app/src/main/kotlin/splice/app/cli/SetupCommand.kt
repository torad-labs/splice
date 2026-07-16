// NEW: `splice setup` — the one guided flow that turns a fresh install into "claudex just works".
// Materializes the topology, installs the wrapper commands, then walks the OAuth heads and offers
// to sign in to each right now (browser). Non-interactive (piped/CI) → installs + prints the steps.
// :app is wall-exempt for println.
@file:Suppress("StringTemplate") // ANSI-colored console formatting reads clearer with braces

package splice.app.cli

import splice.app.TopologyLoader

public object SetupCommand {

    private const val BOLD = "\u001B[1m"
    private const val DIM = "\u001B[2m"
    private const val GREEN = "\u001B[32m"
    private const val CYAN = "\u001B[36m"
    private const val RESET = "\u001B[0m"

    public suspend fun setup() {
        println("${BOLD}splice setup$RESET ${DIM}— wrap Claude Code with your own model backends$RESET")
        println()

        // 1. topology + wrapper commands (+ the `splice` command itself)
        InstallCommand.init()
        InstallCommand.install("--all")
        InstallCommand.installSelf()
        println()

        // 2. sign in to each OAuth head that isn't authed yet
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val pending = topology.heads.entries.mapNotNull { (key, head) ->
            val provider = topology.providers[head.provider] ?: return@mapNotNull null
            val command = head.claude.command ?: key
            if (provider.auth.kind.endsWith("oauth") && !authPresent(provider.auth.file, provider.auth.kind)) {
                Triple(key, command, provider.auth.kind)
            } else {
                null
            }
        }
        if (pending.isEmpty()) {
            println("${GREEN}✓$RESET all heads are signed in.")
        } else {
            for ((key, command, _) in pending) {
                if (AdminSupport.confirm("Sign in to ${CYAN}$command$RESET now?", default = true)) {
                    LoginCommand.login(key)
                } else {
                    println("  ${DIM}skipped — sign in later with: $command login$RESET")
                }
            }
        }

        // 3. next steps
        println()
        println("${BOLD}You're set.$RESET")
        val commands = topology.heads.map { (k, h) -> h.claude.command ?: k }
        println("  Launch      ${commands.joinToString("$DIM · $RESET") { "$CYAN$it$RESET" }}")
        println("  Dashboard   ${CYAN}splice dashboard$RESET")
        println("  Status      ${CYAN}splice status$RESET")
    }

    private fun authPresent(file: String?, kind: String): Boolean {
        val path = file ?: when (kind) {
            "chatgpt-oauth" -> "~/.codex/auth.json"
            "grok-oauth" -> "~/.grok/auth.json"
            else -> return false
        }
        return AdminSupport.authPresent(path)
    }
}
