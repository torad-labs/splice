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
import kotlin.math.abs

// why: a choice opens its line after nothing but whitespace and an optional selection marker, and the marker
// is whatever glyph the client points with. The digit is 1 to 9 because those are the keys that answer it.
private val CHOICE_LINE = Regex("""^\s*([>❯→*•]\s*)?([1-9])[.)]\s+(\S.*)$""")

// why: the client opens a prompt's panel with a solid rule across the screen, and frames what it will run (a
// command, a diff's path) between dashed rules inside it. Those rules are the drawing's structure, not its words.
private val PANEL_RULE = Regex("""^\s*─{3,}\s*$""")
private val FRAME_RULE = Regex("""^\s*╌{3,}\s*$""")

// why: the client's input box opens with a rule (its session's name may sit at its end) and its first line with the
// prompt mark at the margin; a choice's pointer is indented, so the two never meet.
private val INPUT_EDGE = Regex("""^─{3,}.*$""")

// why: a menu the client draws WITHOUT numbers (Claude Code 2.1.296's folder trust question: "❯ No, exit" over
// "Yes, I trust this folder") points at its option from an indent; the input box's own mark sits at the margin, so an
// indented pointer is never the prompt. Its digits answer nothing (pressed on a live pane, Oct 12), so such a menu is
// answered by moving the pointer, which [ScreenOffer.pointed] tells the drive.
private val POINTER_LINE = Regex("""^ +❯[ \u00A0]+(\S.*)$""")

// Claude Code 2.1.296 follows its prompt arrow with a no-break space (U+00A0); a plain space is taken too.
private val PROMPT_LINE = Regex("""^❯[ \u00A0]?(.*)$""")

// why: an empty input box of a session with no message yet shows the client's own suggestion in grey (Claude Code
// 2.1.301: `Try "edit <filepath> to..."`). A captured screen carries no colour, so the suggestion reads like words
// somebody typed and a send is refused for them. Every suggestion the client writes is `Try "` ... `"` on one line.
private val SUGGESTION = Regex("""^Try ".*"$""")

// why: how a numbered choice is named in the contract, so the digit on the screen names the key that answers it.
private const val CHOICE = "CHOICE_"

/** One choice the session is showing: the [key] that answers it and the [label] the client drew for it.
 *  [here] is true for the option the client is pointing at, which is what ACCEPT would take. */
public data class ScreenChoice(val key: SessionKey, val label: String, val here: Boolean)

/** A menu names its options by the digits 1 to 9. */
private const val MENU_KEYS = 9

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
    /** What the prompt holds when nothing is offered: words the person typed there, or a stopped message the client
     *  put back. Empty when the prompt is empty or could not be read. */
    val draft: String = "",
    /** True when the client drew its choices without numbers: a choice is answered by moving the pointer to it and
     *  accepting, never by its digit, which such a menu ignores. */
    val pointed: Boolean = false,
) {
    /** Whether the screen is offering anything to press. Nothing offered is not "nothing pending". */
    public val offering: Boolean get() = choices.isNotEmpty()

    /** Whether the [choice]-th option no longer reads [label], the words the page drew under the click: a screen
     *  that changed under it is never answered with whatever now sits at that place. No label checks nothing. */
    public fun moved(choice: Int, label: String?): Boolean =
        label != null && choices.getOrNull(choice - 1)?.label != label

    /** The keys that answer the [choice]-th option as the person would: its [digit] on a numbered menu, and on a
     *  pointed one the moves from where the pointer sits to it, then Enter. */
    public fun keys(choice: Int, digit: SessionKey): List<SessionKey> {
        if (!pointed) return listOf(digit)
        val from = choices.indexOfFirst { it.here }.coerceAtLeast(0)
        val step = if (choice - 1 > from) SessionKey.NEXT else SessionKey.PREVIOUS
        return List(abs(choice - 1 - from)) { step } + SessionKey.ACCEPT
    }
}

/** Reads the numbered choices off a session's screen, exactly as the client drew them.
 *
 *  PUBLIC BECAUSE A ROUTE TAKES IT. The routes that serve these choices live in this module — Teams' slot
 *  read and Sessions' own — and each is a public class the control plane mounts from :app, so this type is
 *  part of their signature and justified by it (PublicSurfaceLawTest propagates justification through
 *  public signatures). Internal reds those routes instead: a public function cannot expose an internal
 *  parameter type. */
public class ScreenChoices {
    /** The keys a menu's options are named by, 1 to 9 in reading order. */
    private val choiceKeys = (1..MENU_KEYS).map { SessionKey.valueOf(CHOICE + it) }

    /** What [screen] is offering. A screen splice cannot read a choice on offers nothing. */
    public fun on(screen: String): ScreenOffer {
        val lines = screen.lines()
        val block = lastBlock(lines)?.takeUnless { inputBoxFollows(lines, it) }
            ?: return pointerMenu(lines) ?: ScreenOffer("", emptyList(), draft = draft(lines))
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

    /**
     * Whether the client's input box sits below [block]. A permission menu REPLACES the input box while it is up,
     * so a numbered run with the box under it is a list in Claude's last reply, not a menu: offering it would
     * refuse the person's send and draw buttons that type a digit into an idle prompt. The box's mark sits at the
     * margin and a choice's pointer inside the block does not count, because only the lines below it are read.
     */
    private fun inputBoxFollows(lines: List<String>, block: IntRange): Boolean =
        lines.drop(block.last + 1).any { PROMPT_LINE.matches(it) }

    /** A menu drawn without numbers: the indented pointer's line and the lines beside it whose words start in the same
     *  column, keyed 1, 2, 3 in the order a person reads them. Null when no line carries an indented pointer. */
    private fun pointerMenu(lines: List<String>): ScreenOffer? {
        val at = lines.indexOfLast { POINTER_LINE.matches(it) }
        if (at < 0) return null
        val column = lines[at].length - requireNotNull(POINTER_LINE.matchEntire(lines[at])).groupValues[1].length
        val item = { line: String ->
            line.length > column && line.take(
                column,
            ).isBlank() && !line[column].isWhitespace()
        }
        var first = at
        while (first > 0 && item(lines[first - 1])) first -= 1
        var last = at
        while (last < lines.lastIndex && item(lines[last + 1])) last += 1
        val labels = (first..last).map { lines[it].substring(column).trim() }.take(choiceKeys.size)
        val choices = labels.mapIndexed { i, label -> ScreenChoice(choiceKeys[i], label, here = first + i == at) }
        return ScreenOffer(asked(lines, first), choices, panel(lines, first), pointed = true)
    }

    /** The line's three parts, named by CHOICE_LINE's own order: the pointer, the digit, the words. */
    private fun drawn(hit: MatchResult): ScreenChoice {
        val (marker, number, label) = hit.destructured
        return ScreenChoice(SessionKey.valueOf(CHOICE + number), label.trim(), here = marker.isNotEmpty())
    }

    /** The words in the client's input box: its prompt line and the lines under it down to the box's closing rule, with
     *  the box's indent taken off. A prompt line that does not sit under a rule is a past message, not the box. */
    private fun draft(lines: List<String>): String {
        val at = lines.indexOfLast { PROMPT_LINE.matches(it) }
        if (at < 1 || !INPUT_EDGE.matches(lines[at - 1])) return ""
        val first = PROMPT_LINE.matchEntire(lines[at])?.groupValues?.get(1).orEmpty()
        val more = lines.drop(at + 1).takeWhile { !INPUT_EDGE.matches(it) }.map { it.removePrefix("  ") }
        val typed = (listOf(first) + more).joinToString("\n").trimEnd()
        return if (SUGGESTION.matches(typed)) "" else typed
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
