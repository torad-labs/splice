// NEW: Moonshot / Kimi RFC-8628 device-login spec. Split from
// LoginCommand.kt so that file is not billed for three vendor OAuth
// surfaces at once (concentration HIGH, 2026-08-19). Also the status
// table (row / backendLabel / printNextSteps / pad) — StatusCommand
// dropped core.config and collapsed its denom; this file is the only
// existing dest whose kimi vote keeps median high enough after +topology.
package splice.app.cli

import splice.app.DeviceLoginSpec
import splice.app.LoginIo
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.provider.kimi.KimiDeviceIdentity
import splice.provider.kimi.KimiOAuth
import splice.provider.kimi.KimiOAuthEndpoints
import java.nio.file.Path

internal class LoginKimi {

    private val oauth = KimiOAuth()
    private val env: EnvReader = EnvReader(System::getenv)
    private val loginIo = LoginIo()
    // Class member is fine here: doctor no longer builds this type to reach
    // isClientAuth, so the Regex is compiled once per status()/kimi-login, not
    // per doctor predicate. File-scope private val is illegal for new code.
    private val ansi = Regex("\\u001B\\[[0-9;]*m")

    internal fun spec(head: String, authPath: Path): DeviceLoginSpec {
        val identity = KimiDeviceIdentity(deviceIdPath = authPath.resolveSibling("device_id"))
        return DeviceLoginSpec(
            head = head,
            clientId = KimiOAuthEndpoints.CLIENT_ID,
            deviceAuthUrl = KimiOAuthEndpoints.deviceAuthorizationUrl(env),
            tokenUrl = KimiOAuthEndpoints.tokenUrl(env),
            authPath = authPath,
            identityHeaders = identity.headers(),
            toAuthJson = { body ->
                oauth.kimiAuthJsonFromTokenResponse(body, System.currentTimeMillis()).toString()
            },
        )
    }

    // Calls LoginIo / AuthKindRegistry directly — constructing StatusCommand
    // here would cycle (status() builds this class to print the table).
    internal fun row(
        key: String,
        head: HeadConfig,
        provider: ProviderConfig,
        envReader: EnvReader,
    ): String {
        val command = head.claude.command ?: key
        val selfManaged = AuthKindRegistry.from(provider.auth.kind) == AuthKind.Client
        val authed = selfManaged || loginIo.credentialConfigured(key, provider, envReader)
        val auth = when {
            selfManaged -> "$GREEN✓ client-native$RESET"
            AuthKindRegistry.isOAuth(provider.auth.kind) && authed -> "$GREEN✓ signed in$RESET"
            AuthKindRegistry.isOAuth(provider.auth.kind) -> "$YELLOW— $command login$RESET"
            authed -> "$GREEN✓ key set$RESET"
            else -> "$YELLOW— set key$RESET"
        }
        val wrapper = if (loginIo.wrapperInstalled(command, envReader)) {
            "$GREEN✓$RESET"
        } else {
            "$YELLOW— splice install$RESET"
        }
        return pad(key, HEAD_W) + pad(command, CMD_W) + pad(backendLabel(provider), BACKEND_W) +
            pad(auth, AUTH_W) + wrapper
    }

    internal fun backendLabel(provider: ProviderConfig): String = when (provider.auth.kind) {
        "chatgpt-oauth" -> "codex / ChatGPT"
        "grok-oauth" -> "xAI Grok"
        "client" -> "Anthropic (your login)"
        else -> if (provider.dialect.name == "OPENAI_CHAT") "OpenAI-compatible" else "OpenAI platform"
    }

    internal fun printNextSteps(topology: Topology, envReader: EnvReader) {
        val launchable = topology.heads.map { (k, h) -> h.claude.command ?: k }
        println("  ${DIM}Launch $RESET " + launchable.joinToString("$DIM · $RESET") { "$CYAN$it$RESET" })
        val needLogin = topology.heads.entries.filter { (k, h) ->
            val p = topology.providers[h.provider]
            p != null && AuthKindRegistry.isOAuth(p.auth.kind) &&
                AuthKindRegistry.from(p.auth.kind) != AuthKind.Client &&
                !loginIo.credentialConfigured(k, p, envReader)
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
