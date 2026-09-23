// NEW: the five-tone CLI palette, beside CliStyle.kt rather than inside it — that file is a
// byte-for-byte port and every CLI feature imports its constants, so growing it churns a file whose
// whole value is that it does not change.
//
// WHY FIVE TONES AND NOT THE EIGHT ANSI COLOURS. The surfaces these serve (`splice status`,
// `splice doctor`, the setup wizard's close) each answer one question, and the answer is a STATE:
// carrying, straining, dead, or merely structural. Naming a tone after the state rather than the
// colour means a renderer cannot reach for "yellow because it looks nice" — there is no yellow
// here, there is STRAIN, and a row either is strained or it is not.
//
// COLOUR IS NEVER THE ONLY CARRIER. Every state with a tone also has a distinct glyph, so these
// surfaces read the same with colour stripped. That is what makes [ColorDepth.NONE] a supported
// mode rather than a degraded one.
package splice.core.terminal

import splice.core.util.EnvReader

/** How much colour the attached terminal may be given. Resolved once, from the environment. */
public enum class ColorDepth {
    /** `NO_COLOR` is set, or the terminal declares itself dumb. No escape bytes at all — not even
     *  bold or dim, which are attributes rather than colours: see [CliPalette.strong]. */
    NONE,

    /** The eight ANSI colours every terminal since the 1980s renders. */
    BASIC,

    /** 256-colour SGR, which is where the desaturated tones live. */
    EXTENDED,
}

/** SGR prefix for a 256-colour foreground; the tone constants below are its only callers. */
private const val FG = "\u001B[38;5;"

// The extended tones are DESATURATED on purpose. A terminal's own red/green/yellow are tuned for
// alarm, and a status table that is mostly fine must not read as an alarm panel: 114 and 180 sit
// close enough to the foreground to scan as text, far enough to find in a column.
/** 141 violet — splice's own voice: the wordmark, and any command the operator is meant to type. */
private const val SIGNAL_X = "${FG}141m"

/** 114 sage — carrying traffic. Deliberately not the terminal's green, which reads as "success!". */
private const val LIVE_X = "${FG}114m"

/** 180 amber — reachable but degraded: a credential about to expire, a head awaiting sign-in. */
private const val STRAIN_X = "${FG}180m"

/** 167 brick — broken, and the operator must act. Muted so two of them are still readable. */
private const val DEAD_X = "${FG}167m"

/** 245 grey — structure: column headers, units, paths, hints. Everything that is not the answer. */
private const val QUIET_X = "${FG}245m"

/** Magenta is the least-used basic colour in CLI output, so it still reads as "splice speaking". */
private const val SIGNAL_BASIC = "\u001B[35m"

/** The value `TERM` carries when the terminal cannot render anything but text. */
private const val DUMB_TERM = "dumb"

/** The `TERM` infix every 256-colour terminfo entry carries (`xterm-256color`, `screen-256color`). */
private const val TERM_256 = "256color"

/**
 * The five tones a renderer may use, already resolved for this terminal.
 *
 * Construct ONE per command and thread it. Resolving per line would read the environment per line,
 * and a palette that can change between two rows of one table is not a palette.
 */
public class CliPalette(public val depth: ColorDepth) {

    /** splice's own voice, and any command the operator should type. */
    public val signal: String = when (depth) {
        ColorDepth.EXTENDED -> SIGNAL_X
        ColorDepth.BASIC -> SIGNAL_BASIC
        ColorDepth.NONE -> ""
    }

    /** Carrying traffic. */
    public val live: String = pick(GREEN, LIVE_X)

    /** Degraded but reachable. */
    public val strain: String = pick(YELLOW, STRAIN_X)

    /** Broken; the operator must act. */
    public val dead: String = pick(RED, DEAD_X)

    /** Structure rather than answer. */
    public val quiet: String = pick(DIM, QUIET_X)

    /** Emphasis. Bold and dim are ATTRIBUTES rather than colours, and an earlier cut of this class
     *  kept them at [ColorDepth.NONE] on that reasoning. A row arm in StatusTableTest killed it:
     *  the contract worth having is that NONE renders EXACTLY the plain text, because that is the
     *  one a caller can check in a single assertion and a reader can predict without knowing which
     *  SGR codes count as colour. Hierarchy at NONE is carried by the glyphs, the columns and the
     *  indentation, all of which survive a pipe — which is the whole claim these surfaces make. */
    public val strong: String = if (depth == ColorDepth.NONE) "" else BOLD

    /** Ends any tone above, so no caller has to ask whether colour was used. */
    public val off: String = if (depth == ColorDepth.NONE) "" else RESET

    private fun pick(basic: String, extended: String): String = when (depth) {
        ColorDepth.EXTENDED -> extended
        ColorDepth.BASIC -> basic
        ColorDepth.NONE -> ""
    }

    /** Wrap [text] in [tone] and close it. An empty tone returns [text] untouched, so no caller
     *  needs a branch and no stray reset lands in a piped log. */
    public fun paint(tone: String, text: String): String = if (tone.isEmpty()) text else "$tone$text$off"
}

/**
 * Reads how much colour the attached terminal supports.
 *
 * `NO_COLOR` wins over everything, per no-color.org: its PRESENCE — at any value, including the
 * empty string — means no colour. Testing it for "true" or "1" is the usual way to get this wrong,
 * and an operator who exported a bare `NO_COLOR=` has asked and must be obeyed.
 */
public class ColorDepthProbe(private val env: EnvReader) {

    /** The depth for this process. Cheap, but call it once and keep the [CliPalette] it feeds. */
    public fun depth(): ColorDepth {
        val term = env("TERM").orEmpty()
        return when {
            env("NO_COLOR") != null -> ColorDepth.NONE
            term.isEmpty() || term == DUMB_TERM -> ColorDepth.NONE
            term.contains(TERM_256) || !env("COLORTERM").isNullOrBlank() -> ColorDepth.EXTENDED
            else -> ColorDepth.BASIC
        }
    }
}
