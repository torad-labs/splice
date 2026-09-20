// NEW: V4-176 — the wizard's terminal seams as ONE bundle.
//
// WHY, and it is the constructor-width wall's own remedy rather than a tidy-up: SetupCommand sat at
// exactly 12 injected seams, and V4-175's two (the lane question and the wrap call) put it at 14 —
// "over 12 params / 6 subsystems", which detekt cannot see because it ignores defaulted parameters,
// which is why that wall exists. The wall offers two answers and names decomposition first.
//
// THE SPLIT IS REAL, not arithmetic. Everything here is HOW THE WIZARD TALKS TO A TERMINAL: it
// paints a frame, asks a question, spins, and knows whether anyone is watching. Every one of them is
// replaced together in a test (a StringBuilder, canned answers, a non-TTY spinner) and never one at
// a time, which is the signal that they were always one collaborator passed as six. What stays on
// SetupCommand is the other kind: things that CHANGE THE MACHINE — install, add a profile, restart,
// sign in, wrap.
//
// A data class, so detekt's LongParameterList exemption applies for the same reason HeadStores has
// one: a bundle of named values is not a wide function.
package splice.app.cli.setup

import splice.app.cli.HeadPicker
import splice.app.cli.StartChoice
import splice.app.cli.prompt.ConsolePresence
import splice.app.cli.prompt.KeyReader
import splice.app.cli.prompt.MultiSelectPrompt
import splice.app.cli.prompt.SelectPrompt
import splice.app.cli.prompt.Spinner
import splice.app.cli.prompt.TerminalMode
import splice.app.cli.prompt.WizardFrame

internal data class SetupPrompts(
    val frame: WizardFrame = WizardFrame(),
    /** The starting-point menu. */
    val choose: StartChoice = StartChoice { options, index ->
        SelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out).ask("Starting point", options, index)
    },
    /** The tick list of heads to add. */
    val pickHeads: HeadPicker = HeadPicker { options, initial ->
        MultiSelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out)
            .ask("Heads to add", options, initial, minimum = 0)
    },
    /** V4-175: Separate or Wrap for the Claude head. SelectPrompt answers a consoleless ask with the
     *  option at the index it was given, which is what makes SEPARATE the non-TTY default. */
    val pickLane: LanePicker = LanePicker { options, initialIndex ->
        SelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out).ask("Claude lane", options, initialIndex)
    },
    val spinner: Spinner = Spinner(),
    /** Whether anyone is watching: it decides which heads start ticked, never what is offered. */
    val hasConsole: ConsolePresence = ConsolePresence { System.console() != null },
)
