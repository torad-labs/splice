// NEW: `splice status` — the "is it working / am I signed in" view a user reaches for. Reads the
// topology + auth files + wrapper symlinks + daemon liveness, no daemon required. :app: println ok.

package splice.app.cli

import splice.app.TopologyLoader
import splice.core.config.InstallPaths
import splice.core.topology.AuthKind
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.effectiveApiKeyEnv
import java.nio.file.Files
import java.nio.file.Paths

private const val RESET = "\u001B[0m"
private const val DIM = "\u001B[2m"
private const val BOLD = "\u001B[1m"
private const val GREEN = "\u001B[32m"
private const val YELLOW = "\u001B[33m"
private const val CYAN = "\u001B[36m"
private val ansi = Regex("\\u001B\\[[0-9;]*m")

internal fun status(envReader: (String) -> String? = System::getenv) {
    val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
    val port = AdminSupport.controlPort()
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
        println("  " + row(key, head, provider, envReader))
    }
    println()
    printNextSteps(topology, envReader)
}

private fun row(
    key: String,
    head: HeadConfig,
    provider: ProviderConfig,
    envReader: (String) -> String?,
): String {
    val command = head.claude.command ?: key
    val authed = authPresent(key, provider, envReader)
    val auth = when {
        AuthKind.isOAuth(provider.auth.kind) && authed -> "$GREEN✓ signed in$RESET"
        AuthKind.isOAuth(provider.auth.kind) -> "$YELLOW— $command login$RESET"
        authed -> "$GREEN✓ key set$RESET"
        else -> "$YELLOW— set key$RESET"
    }
    val wrapper = if (wrapperInstalled(command, envReader)) "$GREEN✓$RESET" else "$YELLOW— splice install$RESET"
    return pad(key, HEAD_W) + pad(command, CMD_W) + pad(backendLabel(provider), BACKEND_W) +
        pad(auth, AUTH_W) + wrapper
}

private fun backendLabel(provider: ProviderConfig): String = when (provider.auth.kind) {
    "chatgpt-oauth" -> "codex / ChatGPT"
    "grok-oauth" -> "xAI Grok"
    else -> if (provider.dialect.name == "OPENAI_CHAT") "OpenAI-compatible" else "OpenAI platform"
}

internal fun authPresent(key: String, provider: ProviderConfig, envReader: (String) -> String?): Boolean {
    val file = provider.auth.file ?: AuthKind.defaultAuthFileFor(provider.auth.kind)
    val filePresent = file?.let { Files.exists(Paths.get(TopologyLoader.expandHome(it))) } == true
    // OAuth heads authenticate by file only; api-key heads read the effective env var (the explicit
    // auth.env OR the derived <KEY>_API_KEY default the daemon wires) so the derived path matches.
    val envVar = if (AuthKind.isOAuth(provider.auth.kind)) {
        provider.auth.env
    } else {
        effectiveApiKeyEnv(key, provider.auth)
    }
    val envPresent = envVar?.let { envReader(it)?.isNotBlank() } == true
    return filePresent || envPresent
}

internal fun wrapperInstalled(command: String, envReader: (String) -> String?): Boolean =
    Files.isSymbolicLink(InstallPaths(envReader = envReader).binDir.resolve(command))

private fun printNextSteps(topology: Topology, envReader: (String) -> String?) {
    val launchable = topology.heads.map { (k, h) -> h.claude.command ?: k }
    println("  ${DIM}Launch $RESET " + launchable.joinToString("$DIM · $RESET") { "$CYAN$it$RESET" })
    val needLogin = topology.heads.entries.filter { (k, h) ->
        val p = topology.providers[h.provider]
        p != null && AuthKind.isOAuth(p.auth.kind) && !authPresent(k, p, envReader)
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

private const val HEAD_W = 14
private const val CMD_W = 15
private const val BACKEND_W = 23
private const val AUTH_W = 14
