// NEW: status table (lines / backendLabel / dialectLabel / pad) extracted from LoginKimi so that
// file keeps only the Kimi device-login spec. StatusCommand constructs this.
package splice.app.cli.status

import splice.app.cli.auth.CliSignIn
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepthProbe
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.LocalTimeText
import splice.core.util.WallClock
import java.util.concurrent.TimeUnit

internal class StatusTable(
    private val palette: CliPalette = CliPalette(ColorDepthProbe(EnvReader(System::getenv)).depth()),
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val times: LocalTimeText = LocalTimeText(),
) {
    private val signIn = CliSignIn()

    // Class member is fine here: doctor no longer builds this type to reach
    // isClientAuth, so the Regex is compiled once per status(), not per doctor predicate.
    private val ansi = Regex("\\u001B\\[[0-9;]*m")

    /**
     * The whole table — the header, then one row per head whose provider resolves — laid out as ONE
     * grid: every column is as wide as its widest cell, header label included, measured from the
     * rows about to be printed.
     *
     * The widths used to be constants sized to the longest value in the shipped EXAMPLE topology —
     * a denominator taken from a list rather than from the source. On the operator's own machine
     * claude-deepseek, claude-bonsai-second and "local runtime (OpenAI-compatible)" each overran
     * their column and every column after them went ragged, while the alignment arm, built from
     * short names, stayed green. Rows rendered one at a time cannot agree on a width at all:
     * alignment is a property of the table, so the table is the unit.
     */
    internal fun lines(
        topology: Topology,
        envReader: EnvReader,
        failedHeads: Map<String, String> = emptyMap(),
        quotaResetAtEpochSeconds: Map<String, Long> = emptyMap(),
        runtimeNotAnswering: Map<String, String> = emptyMap(),
    ): List<String> {
        // V4-406: a row per configured head, and one per head the daemon names failed that this
        // topology does not know — the join to providers used to DROP a head it could not resolve,
        // hiding exactly the head whose boot failure the operator needs to read.
        val configured = topology.heads.map { (key, head) ->
            val word = HeadWord(failedHeads[key], quotaResetAtEpochSeconds[key], runtimeNotAnswering[key])
            topology.providers[head.provider]?.let { row(key, head, it, envReader, word) }
                ?: unresolvedRow(
                    listOf(key, head.claude.command ?: key, head.port.toString(), "-"),
                    word.bootFailure ?: "unknown provider '${head.provider}'",
                )
        }
        val unknown = failedHeads.filterKeys { it !in topology.heads }.toSortedMap().map { (key, reason) ->
            unresolvedRow(listOf(key, "-", "-", "-"), reason)
        }
        val rows = configured + unknown
        // Each width is the larger of the header label and the widest cell, so it never maxes over
        // an empty list: zero heads still lays out its header before the setup action. DR-173 was that shape in doctor
        // — `maxOf` over an empty section threw on a running daemon with no heads.
        val widths = COLUMNS.mapIndexed { i, label ->
            maxOf(label.length, rows.maxOfOrNull { visible(it.cells[i]) } ?: 0) + GAP
        }
        // The last label goes unpadded: nothing follows it in the header, and the action column
        // below it has no label because "ready" or a command reads as its own heading.
        val labels = COLUMNS.mapIndexed { i, label -> if (i == COLUMNS.lastIndex) label else pad(label, widths[i]) }
        // Four leading spaces: two of indent plus the glyph gutter every row opens with, so each
        // label sits over its data rather than over the glyphs.
        val header = "    " + palette.paint(palette.quiet, labels.joinToString(""))
        val data = rows.map { row ->
            "  ${row.glyph} " + row.cells.mapIndexed { i, cell -> pad(cell, widths[i]) }.joinToString("") + row.action
        }
        val setup = if (topology.heads.isEmpty()) {
            listOf(
                "  " + palette.paint(palette.quiet, "not set up yet; connect a plan: ") +
                    palette.paint(palette.signal, "splice setup"),
            )
        } else {
            emptyList()
        }
        return listOf(header) + data + setup
    }

    // Calls CliSignIn / AuthKindRegistry directly — constructing StatusCommand
    // here would cycle (status() builds this class to print the table).
    private fun row(
        key: String,
        head: HeadConfig,
        provider: ProviderConfig,
        envReader: EnvReader,
        word: HeadWord,
    ): Row {
        val bootFailure = word.bootFailure
        val command = head.claude.command ?: key
        val selfManaged = AuthKindRegistry.from(provider.auth.kind) == AuthKind.Client
        val authed = selfManaged || signIn.credentialConfigured(key, provider, envReader)
        val wrapped = signIn.wrapperInstalled(command, envReader)
        val refusal = refusal(word)
        // ONE actionable column, not two state columns. A row is ready or it names the single
        // command that would make it ready, so the operator never has to work out which of
        // "wrapper missing" and "not signed in" to act on first. V4-394: the running daemon's word
        // outranks both, because a head it could not build serves nothing however it is set up.
        val action = bootFailure?.let { palette.paint(palette.strain, "not running: $it") }
            ?: action(selfManaged, authed, wrapped, command, refusal)
        val configured = authed && wrapped && refusal == null
        val glyph = if (bootFailure == null && configured) {
            palette.paint(palette.live, LIVE_GLYPH)
        } else {
            palette.paint(palette.strain, STRAIN_GLYPH)
        }
        return Row(glyph, listOf(key, command, head.port.toString(), backendLabel(provider)), action)
    }

    /** What stops a turn on a head that is otherwise set up, or null. A missing wrapper or login still
     *  names the command that fixes it first ([action]).
     *
     *  V4-398: a provider that refuses until a known instant is not ready however it is set up; a
     *  reset that has already passed reads ready, since the daemon drops it on its own once the
     *  provider recovers. V4-415: the same for a local runtime that does not answer, whose word
     *  outranks a quota it has none of. */
    private fun refusal(word: HeadWord): String? {
        word.runtimeEndpoint?.let { return "runtime not answering on $it" }
        val resetAt = word.quotaResetAtEpochSeconds?.takeIf { TimeUnit.SECONDS.toMillis(it) > clock() }
        return resetAt?.let { "out of quota until ${times.at(it)}" }
    }

    /** A head whose provider the topology cannot resolve, or that only the daemon knows: it cannot
     *  serve, so the row says not running with the daemon's reason, else the config's own. */
    private fun unresolvedRow(cells: List<String>, reason: String): Row =
        Row(
            palette.paint(palette.strain, STRAIN_GLYPH),
            cells,
            palette.paint(palette.strain, "not running: $reason"),
        )

    /** The one thing to do about this row, or a dim "ready" when there is nothing. The command is
     *  painted in splice's own tone because it is meant to be TYPED; the ready state is not.
     *
     *  A missing credential is `<command> login` whatever the auth kind. An earlier cut said "set
     *  the api key" for api-key heads, which names no variable, no file and no verb — and the real
     *  fix was a command all along: the launch shim routes `<command> login` to LoginCommand for
     *  every head, and LoginCommand prompts an api-key head for its key and stores it where the
     *  daemon reads it. */
    private fun action(
        selfManaged: Boolean,
        authed: Boolean,
        wrapped: Boolean,
        command: String,
        refusal: String?,
    ): String = when {
        !wrapped -> palette.paint(palette.signal, "splice install")
        !authed -> palette.paint(palette.signal, "$command login")
        refusal != null -> palette.paint(palette.strain, refusal)
        else -> palette.paint(palette.quiet, if (selfManaged) "ready (your login)" else "ready")
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

    // pad by VISIBLE width (ANSI escapes don't count toward column alignment).
    private fun pad(s: String, w: Int): String = s + " ".repeat((w - visible(s)).coerceAtLeast(1))

    private fun visible(s: String): Int = ansi.replace(s, "").length
}

/** One head, before layout: the state glyph, the cells [COLUMNS] names, and the action — which is
 *  unpadded because nothing follows it on the line. */
private data class Row(val glyph: String, val cells: List<String>, val action: String)

/** What status learned about one head beyond its config: why the daemon could not boot it (V4-394),
 *  the instant its provider stops refusing turns, epoch seconds (V4-398), and the endpoint of its
 *  local runtime when that does not answer (V4-415). All null for a head that is fine. */
private data class HeadWord(
    val bootFailure: String?,
    val quotaResetAtEpochSeconds: Long?,
    val runtimeEndpoint: String?,
)

/** The labelled columns, in order. A row's cells line up with these by index. */
private val COLUMNS = listOf("head", "command", "port", "upstream")

// Why 3: with no rules between columns, whitespace is the only separator, and cells are free text
// whose own words are one space apart ("local runtime (OpenAI-compatible)", "codex / ChatGPT"). A
// two-space gap is barely wider than a gap inside a cell; three reads as a column boundary.
private const val GAP = 3

// The state glyphs. These are the reason colour can be confined to one character per row, and the
// reason the table still reads with colour stripped — so they must stay visually distinct as SHAPES,
// not merely as tones. Both are single-width in every terminal font splice has been run in.
private const val LIVE_GLYPH = "▸"

private const val STRAIN_GLYPH = "!"
