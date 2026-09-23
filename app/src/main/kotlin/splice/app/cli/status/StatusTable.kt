// NEW: status table (row / backendLabel / dialectLabel / printNextSteps / pad) extracted from
// LoginKimi so that file keeps only the Kimi device-login spec. StatusCommand constructs this.
package splice.app.cli.status

import splice.app.auth.LoginIo
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepthProbe
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader

internal class StatusTable(
    private val palette: CliPalette = CliPalette(ColorDepthProbe(EnvReader(System::getenv)).depth()),
) {

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
        val wrapped = loginIo.wrapperInstalled(command, envReader)
        // ONE actionable column, not two state columns. A row is ready or it names the single
        // command that would make it ready, so the operator never has to work out which of
        // "wrapper missing" and "not signed in" to act on first.
        val action = action(selfManaged, authed, wrapped, provider.auth.kind, command)
        val glyph = if (authed && wrapped) {
            palette.paint(palette.live, LIVE_GLYPH)
        } else {
            palette.paint(palette.strain, STRAIN_GLYPH)
        }
        return "$glyph " + pad(key, HEAD_W) + pad(command, CMD_W) + pad(head.port.toString(), PORT_W) +
            pad(backendLabel(provider), BACKEND_W) + action
    }

    /** The one thing to do about this row, or a dim "ready" when there is nothing. The command is
     *  painted in splice's own tone because it is meant to be TYPED; the ready state is not. */
    private fun action(
        selfManaged: Boolean,
        authed: Boolean,
        wrapped: Boolean,
        kind: String,
        command: String,
    ): String = when {
        !wrapped -> palette.paint(palette.signal, "splice install")
        authed -> palette.paint(palette.quiet, if (selfManaged) "ready (your login)" else "ready")
        AuthKindRegistry.isOAuth(kind) -> palette.paint(palette.signal, "$command login")
        else -> palette.paint(palette.signal, "set the api key")
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
        // Separated by spacing, not by a middle-dot join: the dots read as content in a monospace
        // grid, and every command here is already one unbroken token.
        val launchList = launchable.joinToString("   ") { palette.paint(palette.signal, it) }
        println("  " + palette.paint(palette.quiet, "launch  ") + launchList)
        val needLogin = topology.heads.entries.filter { (k, h) ->
            val p = topology.providers[h.provider]
            p != null && AuthKindRegistry.isOAuth(p.auth.kind) &&
                AuthKindRegistry.from(p.auth.kind) != AuthKind.Client &&
                !loginIo.credentialConfigured(k, p, envReader)
        }.map { (k, h) -> h.claude.command ?: k }
        if (needLogin.isNotEmpty()) {
            val loginList = needLogin.joinToString("   ") { palette.paint(palette.signal, "$it login") }
            println("  " + palette.paint(palette.quiet, "sign in ") + loginList)
        }
        println("  " + palette.paint(palette.quiet, "panel   ") + palette.paint(palette.signal, "splice dashboard"))
    }

    // pad by VISIBLE width (ANSI escapes don't count toward column alignment).
    private fun pad(s: String, w: Int): String {
        val visible = ansi.replace(s, "").length
        return s + " ".repeat((w - visible).coerceAtLeast(1))
    }
}

// Column widths are VISIBLE width; pad() strips SGR before measuring. Sized to the longest real
// value each column carries rather than to a round number: head keys and wrapper commands come from
// the shipped example topology, and backendLabel's longest is "Anthropic (your login)" at 22.
private const val HEAD_W = 14
private const val CMD_W = 15
private const val PORT_W = 7
private const val BACKEND_W = 23

// The state glyphs. These are the reason colour can be confined to one character per row, and the
// reason the table still reads with colour stripped — so they must stay visually distinct as SHAPES,
// not merely as tones. Both are single-width in every terminal font splice has been run in.
private const val LIVE_GLYPH = "▸"

private const val STRAIN_GLYPH = "!"

/** Header for [StatusTable.row]'s columns, dimmed by the caller.
 *
 *  FOUR leading spaces, not two: the caller indents each row by two and [row] then opens with a
 *  glyph and a space, so a head name starts at column four. A two-space header lines up with the
 *  GLYPH gutter instead of the data and every column below it reads one notch left. */
internal const val STATUS_HEADER: String = "    head          command        port   upstream"
