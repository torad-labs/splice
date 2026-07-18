// NEW: `splice status` — the "is it working / am I signed in" view a user reaches for. Reads the
// topology + auth files + wrapper symlinks + daemon liveness, no daemon required. :app: println ok.

package splice.app.cli

import splice.app.TopologyLoader
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import java.nio.file.Files
import java.nio.file.Paths

private const val RESET = "\u001B[0m"
private const val DIM = "\u001B[2m"
private const val BOLD = "\u001B[1m"
private const val GREEN = "\u001B[32m"
private const val YELLOW = "\u001B[33m"
private const val CYAN = "\u001B[36m"
private val ansi = Regex("\\u001B\\[[0-9;]*m")

internal fun status() {
    val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
    val port = topology.daemon.controlPort?.takeIf { it > 0 } ?: DEFAULT_PORT
    val up = AdminSupport.daemonUp(port)

    println("${BOLD}splice$RESET $DIM— Claude Code, wrapped$RESET")
    println()
    val daemonLine = if (up) {
        "${GREEN}running$RESET $DIM· control :$port$RESET"
    } else {
        "${YELLOW}stopped$RESET $DIM(starts on first launch)$RESET"
    }
    println("  daemon    $daemonLine")
    println("  config    $DIM${TopologyLoader.configPath()}$RESET")
    println("  jar       $DIM${AdminSupport.selfJar() ?: "not installed — run: splice install"}$RESET")
    println()
    println("  ${BOLD}HEAD          COMMAND        BACKEND                AUTH          WRAPPER$RESET")
    for ((key, head) in topology.heads) {
        val provider = topology.providers[head.provider] ?: continue
        println("  " + row(key, head, provider))
    }
    println()
    printNextSteps(topology)
}

private fun row(key: String, head: HeadConfig, provider: ProviderConfig): String {
    val command = head.claude.command ?: key
    val authed = authPresent(provider)
    val auth = when {
        provider.auth.kind.endsWith("oauth") && authed -> "$GREEN✓ signed in$RESET"
        provider.auth.kind.endsWith("oauth") -> "$YELLOW— $command login$RESET"
        authed -> "$GREEN✓ key set$RESET"
        else -> "$YELLOW— set key$RESET"
    }
    val wrapper = if (wrapperInstalled(command)) "$GREEN✓$RESET" else "$YELLOW— splice install$RESET"
    return pad(key, HEAD_W) + pad(command, CMD_W) + pad(backendLabel(provider), BACKEND_W) +
        pad(auth, AUTH_W) + wrapper
}

private fun backendLabel(provider: ProviderConfig): String = when (provider.auth.kind) {
    "chatgpt-oauth" -> "codex / ChatGPT"
    "grok-oauth" -> "xAI Grok"
    else -> if (provider.dialect.name == "OPENAI_CHAT") "OpenAI-compatible" else "OpenAI platform"
}

private fun authPresent(provider: ProviderConfig): Boolean {
    val file = provider.auth.file ?: defaultAuthFile(provider.auth.kind) ?: return provider.auth.env != null
    return Files.exists(Paths.get(TopologyLoader.expandHome(file)))
}

private fun defaultAuthFile(kind: String): String? = when (kind) {
    "chatgpt-oauth" -> "~/.codex/auth.json"
    "grok-oauth" -> "~/.grok/auth.json"
    else -> null
}

private fun wrapperInstalled(command: String): Boolean =
    Files.isSymbolicLink(AdminSupport.home().resolve(".local").resolve("bin").resolve(command))

private fun printNextSteps(topology: Topology) {
    val launchable = topology.heads.map { (k, h) -> h.claude.command ?: k }
    println("  ${DIM}Launch $RESET " + launchable.joinToString("$DIM · $RESET") { "$CYAN$it$RESET" })
    val needLogin = topology.heads.entries.filter { (_, h) ->
        val p = topology.providers[h.provider]
        p != null && p.auth.kind.endsWith("oauth") && !authPresent(p)
    }.map { (k, h) -> h.claude.command ?: k }
    if (needLogin.isNotEmpty()) {
        println("  ${DIM}Sign in$RESET " + needLogin.joinToString("$DIM · $RESET") { "$CYAN$it login$RESET" })
    }
    println("  ${DIM}Panel  $RESET ${CYAN}splice dashboard$RESET")
}

// pad by VISIBLE width (ANSI escapes don't count toward column alignment).
private fun pad(s: String, w: Int): String {
    val visible = ansi.replace(s, "").length
    return s + " ".repeat((w - visible).coerceAtLeast(1))
}

private const val DEFAULT_PORT = 3096
private const val HEAD_W = 14
private const val CMD_W = 15
private const val BACKEND_W = 23
private const val AUTH_W = 14
