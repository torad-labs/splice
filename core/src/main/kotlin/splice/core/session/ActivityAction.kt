// NEW: Oct 10, 2026 (BUILD.md, Teams: track a team's activity) — what a member just did, as data.
//
// splice composes a 3-5 word sentence from a session's last tool call ("Reading totals.ts"). The console draws the
// tool and its object as elements and never shows that sentence, so the call travels with it: the tool exactly as
// the client named it ("Read", "Bash", "mcp__x__y") and its object, the one argument a person would name (a file's
// name, a command's first words, a pattern, a subagent's type, a recipient). A label with no call behind it
// ("Replying to the user") carries neither, and so does one written before this existed: the console reads those as
// a gap, and nothing parses the sentence back apart.
package splice.core.session

/** One activity sample: the [label] splice composed, and the [tool] call and [subject] it was composed from. */
public data class ActivityAction(val label: String, val tool: String? = null, val subject: String? = null)
