// NEW: v0.4.0 FEATURES.md §1 — the `splice add` command line as data, and its parser. Split from
// AddCommand.kt (concentration, 2026-09-13).
package splice.configuration.add

import splice.core.terminal.TerminalOutput

/** One valued flag applied to the arguments so far. */
internal fun interface FlagSetter {
    operator fun invoke(args: AddArgs, value: String): AddArgs
}

private val VALUED_FLAGS: Map<String, FlagSetter> = mapOf(
    "--name" to FlagSetter { p, v -> p.copy(name = v) },
    "--base-url" to FlagSetter { p, v -> p.copy(baseUrl = v) },
    "--model" to FlagSetter { p, v -> p.copy(models = p.models + v) },
    "--command" to FlagSetter { p, v -> p.copy(command = v) },
    // an unknown answer leaves the question open, so the add asks it or refuses; it never picks for the person
    "--key" to FlagSetter { p, v ->
        p.copy(options = p.options.copy(key = AddKeyChoice.entries.firstOrNull { it.flag == v }))
    },
)

/** One switch (no value) applied to the arguments so far. */
internal fun interface FlagSwitch {
    operator fun invoke(args: AddArgs): AddArgs
}

private val SWITCHES: Map<String, FlagSwitch> = mapOf(
    "--live" to FlagSwitch { p -> p.copy(options = p.options.copy(live = true)) },
    "--yes" to FlagSwitch { p -> p.copy(options = p.options.copy(yes = true)) },
    "-y" to FlagSwitch { p -> p.copy(options = p.options.copy(yes = true)) },
)

internal data class AddArgs(
    val profile: String? = null,
    val name: String? = null,
    val baseUrl: String? = null,
    val models: List<String> = emptyList(),
    val command: String? = null,
    val options: AddOptions = AddOptions(),
)

/** How the add runs: [live] runs the one short turn, [yes] takes every default without asking, and [key] answers whose
 *  key a second command on a provider the file holds sends with (null: not answered yet). */
internal data class AddOptions(
    val live: Boolean = false,
    val yes: Boolean = false,
    val key: AddKeyChoice? = null,
)

/** A second command on a provider the file holds sends with that provider's key, or with a new key of its own, under a
 *  provider of its own in that provider's family (the console's "<Provider>'s key" / "A new key"). */
internal enum class AddKeyChoice(val flag: String) {
    PROVIDER("provider"),
    NEW("new"),
}

internal class AddArgParser {
    /** Null on anything the command line cannot mean: an unknown flag, a valued flag without its
     *  value, or a second positional word (a mistyped flag used to be swallowed here and the add
     *  went on with the defaults, so `--nam foo` wrote a provider the operator never asked for). */
    fun parse(args: List<String>): AddArgs? {
        var parsed = AddArgs()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val valued = VALUED_FLAGS[a]
            val switch = SWITCHES[a]
            parsed = when {
                // `--model --yes` is a model without its value, not a model named "--yes".
                valued != null -> {
                    val value = args.getOrNull(i + 1)?.takeUnless { it.startsWith("-") } ?: return null
                    valued(parsed, value).also { i++ }
                }
                switch != null -> switch(parsed)
                a.startsWith("-") || parsed.profile != null -> return null
                else -> parsed.copy(profile = a)
            }
            i++
        }
        return parsed
    }

    fun usage(output: TerminalOutput): Boolean {
        output.line(
            "usage: splice add <profile> [--name NAME] [--base-url URL] [--model ID[:WINDOW]]... [--command CMD] " +
                "[--key provider|new] [--live] [--yes]",
        )
        return false
    }
}
