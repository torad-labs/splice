// NEW: Oct 10, 2026 — the choices a session is showing RIGHT NOW, read off its screen.
//
// WHY THIS IS A READ AND NOT A LIST. A permission's choices belong to the Claude Code version the person is
// running, not to splice. The words change between versions, the number of them changes, and a list splice
// kept would be wrong the first time the client shipped a new one — quietly, by drawing a button that answers
// something other than what it says. The session-terminal contract says so in its own header: reading the
// screen is an act of its own "because the choices belong to the client's version, never to a list splice
// keeps". So the console draws Allow / Always allow / Deny when that is what is on the screen, and draws
// nothing when it is not.
//
// WHY THE PARSE LIVES HERE AND NOT IN THE TERMINAL. :integrations-tmux knows how to GET a screen; what is
// drawn on it is Claude Code's, the same text whatever terminal shows it. A parse in the tmux module would
// have to be written again the day seatd replaces it, for no reason.
//
// WHAT IT REFUSES TO GUESS. A line is a choice only when it opens with a digit 1 to 9 followed by `.` or
// `)`, after nothing but whitespace and an optional selection marker. "2 files changed" is prose and stays
// prose. A tenth option is not a choice here because [SessionKey] presses 1 to 9 and nothing else, so a
// tenth would be a button that cannot be pressed. FOUND NOTHING IS AN ANSWER: a screen with no numbered
// lines yields an empty list, and the card falls back to telling the person where to answer it. An empty
// list never means "no permission is pending" — it means splice could not read one, which is why the caller
// draws a fallback and not a cleared card.
package splice.sessions.prompt

import splice.core.session.SessionKey

// why: a choice opens its line after nothing but whitespace and an optional selection marker, and the marker
// is whatever glyph the client points with. The digit is 1 to 9 because those are the keys that answer it.
private val CHOICE_LINE = Regex("""^\s*([>❯→*•]\s*)?([1-9])[.)]\s+(\S.*)$""")

// why: the client opens a prompt's panel with a solid rule across the screen, and frames what it will run (a
// command, a diff's path) between dashed rules inside it. Those rules are the drawing's structure, not its words.
private val PANEL_RULE = Regex("""^\s*─{3,}\s*$""")
private val FRAME_RULE = Regex("""^\s*╌{3,}\s*$""")

// why: how a numbered choice is named in the contract, so the digit on the screen names the key that answers it.
private const val CHOICE = "CHOICE_"

/** One choice the session is showing: the [key] that answers it and the [label] the client drew for it.
 *  [here] is true for the option the client is pointing at, which is what ACCEPT would take. */
public data class ScreenChoice(val key: SessionKey, val label: String, val here: Boolean)

/** One line of the panel above a prompt's choices, as the client drew it. [framed] is true for a line the client set
 *  between dashed rules: what the call will run, which a person reads before choosing. */
public data class ScreenLine(val text: String, val framed: Boolean)

/** What a session's screen is offering. [asked] is the text above the first choice, as the client wrote it,
 *  and is empty when the screen opens straight into the choices. [panel] is the whole prompt the client drew above
 *  the choices, from its opening rule down: the tool, what it runs and why, so no one answers a call they cannot see.
 *  It is empty when the screen shows no opening rule above the choices. */
public data class ScreenOffer(
    val asked: String,
    val choices: List<ScreenChoice>,
    val panel: List<ScreenLine> = emptyList(),
) {
    /** Whether the screen is offering anything to press. Nothing offered is not "nothing pending". */
    public val offering: Boolean get() = choices.isNotEmpty()
}

/** Reads the numbered choices off a session's screen, exactly as the client drew them.
 *
 *  PUBLIC BECAUSE A ROUTE TAKES IT. The routes that serve these choices live in this module — Teams' slot
 *  read and Sessions' own — and each is a public class the control plane mounts from :app, so this type is
 *  part of their signature and justified by it (PublicSurfaceLawTest propagates justification through
 *  public signatures). Internal reds those routes instead: a public function cannot expose an internal
 *  parameter type. */
public class ScreenChoices {
    /** What [screen] is offering. A screen splice cannot read a choice on offers nothing. */
    public fun on(screen: String): ScreenOffer {
        val lines = screen.lines()
        val block = lastBlock(lines) ?: return ScreenOffer("", emptyList())
        val choices = block.mapNotNull { at -> CHOICE_LINE.matchEntire(lines[at])?.let { drawn(it) } }
            .distinctBy { it.key }
            .sortedBy { it.key.ordinal }
        return ScreenOffer(asked(lines, block.first), choices, panel(lines, block.first))
    }

    /**
     * The LAST run of choice lines on the screen, or null when there is none.
     *
     * THE LIVE PROMPT IS THE LAST ONE. A pane that has scrolled still holds the tail of an older prompt
     * above the one the person is looking at, and its numbers answer nothing on screen. Reading the whole
     * screen and keeping the lowest line per number is not enough: an older prompt with THREE options above
     * a live one with two leaves the old third behind as a button that presses a digit the live prompt does
     * not offer. So the block is what is read, not the numbers — everything above the first non-choice line
     * is another moment in this session's past.
     */
    private fun lastBlock(lines: List<String>): IntRange? {
        val last = lines.indexOfLast { CHOICE_LINE.matches(it) }
        if (last < 0) return null
        var first = last
        while (first > 0 && CHOICE_LINE.matches(lines[first - 1])) first -= 1
        return first..last
    }

    /** The line's three parts, named by CHOICE_LINE's own order: the pointer, the digit, the words. */
    private fun drawn(hit: MatchResult): ScreenChoice {
        val (marker, number, label) = hit.destructured
        return ScreenChoice(SessionKey.valueOf(CHOICE + number), label.trim(), here = marker.isNotEmpty())
    }

    /** The prompt's panel: every line between the last solid rule above the choices and the first choice, blank
     *  lines left out and the dashed rules turned into [ScreenLine.framed]. No rule above them is no panel. */
    private fun panel(lines: List<String>, firstChoice: Int): List<ScreenLine> {
        val rule = lines.subList(0, firstChoice).indexOfLast { PANEL_RULE.matches(it) }
        if (rule < 0) return emptyList()
        var framed = false
        return lines.subList(rule + 1, firstChoice).mapNotNull { line ->
            if (FRAME_RULE.matches(line)) framed = !framed
            line.trim().takeIf { it.isNotEmpty() && !FRAME_RULE.matches(line) }?.let { ScreenLine(it, framed) }
        }
    }

    /** The question above the choices: the last run of non-empty lines before the first one, which is how
     *  the client separates a prompt from the output it interrupted. Empty when there is nothing above it. */
    private fun asked(lines: List<String>, firstChoice: Int?): String {
        if (firstChoice == null) return ""
        val above = lines.take(firstChoice).dropLastWhile { it.isBlank() }
        return above.takeLastWhile { it.isNotBlank() }.joinToString("\n") { it.trim() }.trim()
    }
}
