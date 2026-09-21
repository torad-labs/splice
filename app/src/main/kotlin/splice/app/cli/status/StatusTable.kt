// NEW: status table (row / backendLabel / dialectLabel / printNextSteps / pad) extracted from
// LoginKimi so that file keeps only the Kimi device-login spec. StatusCommand constructs this.
package splice.app.cli.status

import splice.app.auth.LoginIo
import splice.app.cli.CYAN
import splice.app.cli.DIM
import splice.app.cli.GREEN
import splice.app.cli.RESET
import splice.app.cli.YELLOW
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader

internal class StatusTable {

    private val loginIo = LoginIo()

    // Class member is fine here: doctor no longer builds this type to reach
    // isClientAuth, so the Regex is compiled once per status(), not per doctor predicate.
    private val ansi = Regex("\\u001B\\[[0-9;]*m")

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
        val auth = authLabel(selfManaged, authed, provider.auth.kind, command)
        val wrapper = if (loginIo.wrapperInstalled(command, envReader)) {
            "$GREEN✓$RESET"
        } else {
            "$YELLOW— splice install$RESET"
        }
        return pad(key, HEAD_W) + pad(command, CMD_W) + pad(backendLabel(provider), BACKEND_W) +
            pad(auth, AUTH_W) + wrapper
    }

    private fun authLabel(selfManaged: Boolean, authed: Boolean, kind: String, command: String): String = when {
        selfManaged -> "$GREEN✓ client-native$RESET"
        AuthKindRegistry.isOAuth(kind) && authed -> "$GREEN✓ signed in$RESET"
        AuthKindRegistry.isOAuth(kind) -> "$YELLOW— $command login$RESET"
        authed -> "$GREEN✓ key set$RESET"
        else -> "$YELLOW— set key$RESET"
    }

    /** DR-175: the status table's backend column, and it named the wrong vendor for kimi.
     *
     *  This matched three wire strings and let everything else fall through to a DIALECT guess,
     *  whose own else-branch was the literal "OpenAI platform". kimi ships as dialect
     *  anthropic-passthrough with auth kind kimi-oauth, so it landed in that final else: the head
     *  whose whole point is Moonshot told the operator they were signing in to OpenAI. The
     *  documented api-key alternative in the shipped example config (MOONSHOT_API_KEY over
     *  anthropic-passthrough) read the same way.
     *
     *  The shape was the defect, not the missing branch. AuthKind.kt says knownKinds() exists so
     *  "compatibility matrices derive their denominator from the registry rather than maintaining a
     *  second list that can silently omit a new kind" — and a `when` over wire STRINGS with an else
     *  was exactly that second list. Both `when`s below are exhaustive over a sealed hierarchy and
     *  an enum, so the next registered auth kind or dialect fails to COMPILE here rather than
     *  quietly acquiring a vendor name that has nothing to do with it. */
    internal fun backendLabel(provider: ProviderConfig): String =
        when (AuthKindRegistry.from(provider.auth.kind)) {
            AuthKind.ChatgptOAuth -> "codex / ChatGPT"
            AuthKind.GrokOAuth -> "xAI Grok"
            AuthKind.KimiOAuth -> "Moonshot Kimi"
            AuthKind.MuseOAuth -> "Meta Muse"
            AuthKind.Client -> "Anthropic (your login)"
            // Unregistered kinds — api-key, or an operator's custom scheme, which AuthKind.kt
            // deliberately leaves unregistered. The wire dialect is then the only evidence there
            // is, so the label describes the WIRE and names no vendor it cannot verify. A local
            // runtime (v0.4.0, FEATURES.md §10) says so: no subscription, no quota, the operator's
            // own process.
            null -> dialectLabel(provider.dialect).let { if (provider.isLocal) "local runtime ($it)" else it }
        }

    private fun dialectLabel(dialect: Dialect): String = when (dialect) {
        Dialect.OPENAI_CHAT -> "OpenAI-compatible"
        Dialect.OPENAI_RESPONSES -> "OpenAI platform"
        Dialect.ANTHROPIC_PASSTHROUGH -> "Anthropic-compatible"
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
