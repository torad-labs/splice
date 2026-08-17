// NEW: `splice status` — the "is it working / am I signed in" view a user reaches for. Reads the
// topology + auth files + wrapper symlinks + daemon liveness, no daemon required. :app: println ok.

package splice.app.cli

import splice.app.TopologyLoader
import splice.core.config.InstallPaths
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Paths

private const val RESET = "\u001B[0m"
private const val DIM = "\u001B[2m"
private const val BOLD = "\u001B[1m"
private const val GREEN = "\u001B[32m"
private const val YELLOW = "\u001B[33m"
private const val CYAN = "\u001B[36m"

// FILE SCOPE ON PURPOSE: one compiled Regex shared by every pad() call. As a class member it would
// be recompiled for each StatusCommand instance (doctor builds one per run just to reach
// isClientAuth/authPresent), which is the cost this top-level val exists to avoid.
private val ansi = Regex("\\u001B\\[[0-9;]*m")

/** The `status` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). Also the home of the two credential-presence predicates doctor
 *  reads (isClientAuth / authPresent) — it owns "is this head configured?", so DoctorCommand
 *  constructs one rather than re-deriving them. Every member keeps the old function's name. */
internal class StatusCommand {

    internal fun status(envReader: EnvReader = EnvReader(System::getenv)) {
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
        envReader: EnvReader,
    ): String {
        val command = head.claude.command ?: key
        val authed = authPresent(key, provider, envReader)
        val auth = when {
            isClientAuth(provider) -> "$GREEN✓ client-native$RESET"
            AuthKindRegistry.isOAuth(provider.auth.kind) && authed -> "$GREEN✓ signed in$RESET"
            AuthKindRegistry.isOAuth(provider.auth.kind) -> "$YELLOW— $command login$RESET"
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
        "client" -> "Anthropic (your login)"
        else -> if (provider.dialect.name == "OPENAI_CHAT") "OpenAI-compatible" else "OpenAI platform"
    }

    /** A head that DECLARES the caller's own credential rather than one splice holds.
     *
     *  DECLARED, not observed, and the distinction is load-bearing: the CLI reads the topology TOML
     *  and never the daemon's wired providers. Declaration and wiring agree on the
     *  anthropic-passthrough dialect — the one dispatch arm that builds a ClientAuthProvider — and
     *  on any other dialect the daemon falls through to an api-key provider and keeps enforcing the
     *  mgmt key. So this predicate answers "what does the head declare?", which is all this process
     *  can see; the daemon derives the actual bypass from the wired credential, never from here. */
    internal fun isClientAuth(provider: ProviderConfig): Boolean =
        AuthKindRegistry.from(provider.auth.kind) == AuthKind.Client

    internal fun authPresent(key: String, provider: ProviderConfig, envReader: EnvReader): Boolean =
        // A head that declares client auth has no splice-held credential to configure BY DESIGN, so
        // "is it configured?" is always yes. Without this it falls through to the api-key branch and
        // reads as permanently unconfigured, against a head that serves fine.
        isClientAuth(provider) || credentialConfigured(key, provider, envReader)

    /** File / env / KeyStore presence for a head whose credential SPLICE holds. */
    private fun credentialConfigured(
        key: String,
        provider: ProviderConfig,
        envReader: EnvReader,
    ): Boolean {
        val file = provider.auth.file ?: AuthKindRegistry.defaultAuthFileFor(provider.auth.kind)
        val filePresent = file?.let { Files.exists(Paths.get(TopologyLoader.expandHome(it))) } == true
        // OAuth heads authenticate by file only; api-key heads read the effective env var (the explicit
        // auth.env OR the derived <KEY>_API_KEY default the daemon wires) so the derived path matches.
        val oauth = AuthKindRegistry.isOAuth(provider.auth.kind)
        val envVar = if (oauth) provider.auth.env else provider.auth.effectiveApiKeyEnv(key)
        val envPresent = envVar?.let { envReader(it)?.isNotBlank() } == true
        // The KeyStore is the third presence source for api-key heads — a key stored by
        // `splice key set` / `<head> login` / token capture reads as configured here too.
        val storePresent = !oauth && envVar != null &&
            KeyStore(KeyStorePath.defaultPath(envReader)).read(envVar) != null
        return filePresent || envPresent || storePresent
    }

    internal fun wrapperInstalled(command: String, envReader: EnvReader): Boolean =
        Files.isSymbolicLink(InstallPaths(envReader = envReader).binDir.resolve(command))

    private fun printNextSteps(topology: Topology, envReader: EnvReader) {
        val launchable = topology.heads.map { (k, h) -> h.claude.command ?: k }
        println("  ${DIM}Launch $RESET " + launchable.joinToString("$DIM · $RESET") { "$CYAN$it$RESET" })
        val needLogin = topology.heads.entries.filter { (k, h) ->
            val p = topology.providers[h.provider]
            p != null && AuthKindRegistry.isOAuth(p.auth.kind) && !authPresent(k, p, envReader)
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
}

private const val HEAD_W = 14
private const val CMD_W = 15
private const val BACKEND_W = 23
private const val AUTH_W = 14
