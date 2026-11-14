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

import splice.terminal.ConsolePresence
import splice.terminal.KeyReader
import splice.terminal.MultiSelectPrompt
import splice.terminal.Spinner
import splice.terminal.TerminalMode
import splice.terminal.WizardFrame

internal data class SetupPrompts(
    val frame: WizardFrame = WizardFrame(),
    /** The tick list of plans to connect: the wizard's first question. */
    val pickHeads: HeadPicker = HeadPicker { options, initial ->
        MultiSelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out)
            .ask("Plans to connect", options, initial, minimum = 0)
    },
    /** A ticked head's models to write (spec section 1), at least one. */
    val pickModels: ModelPicker = ModelPicker { question, options, initial ->
        MultiSelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out)
            .ask(question, options, initial, minimum = 1)
    },
    val spinner: Spinner = Spinner(),
    /** Whether anyone is watching: it decides which heads start ticked, never what is offered. */
    val hasConsole: ConsolePresence = ConsolePresence { System.console() != null },
)
