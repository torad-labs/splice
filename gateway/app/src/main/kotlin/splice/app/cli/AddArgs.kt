// NEW: v0.4.0 FEATURES.md §1 — the `splice add` command line as data, and its parser. Split from
// AddCommand.kt (concentration, 2026-09-13).
package splice.app.cli

/** One valued flag applied to the arguments so far. */
internal fun interface FlagSetter {
    operator fun invoke(args: AddArgs, value: String): AddArgs
}

private val VALUED_FLAGS: Map<String, FlagSetter> = mapOf(
    "--name" to FlagSetter { p, v -> p.copy(name = v) },
    "--base-url" to FlagSetter { p, v -> p.copy(baseUrl = v) },
    "--model" to FlagSetter { p, v -> p.copy(models = p.models + v) },
    "--command" to FlagSetter { p, v -> p.copy(command = v) },
)

/** One switch (no value) applied to the arguments so far. */
internal fun interface FlagSwitch {
    operator fun invoke(args: AddArgs): AddArgs
}

private val SWITCHES: Map<String, FlagSwitch> = mapOf(
    "--live" to FlagSwitch { p -> p.copy(live = true) },
    "--yes" to FlagSwitch { p -> p.copy(yes = true) },
    "-y" to FlagSwitch { p -> p.copy(yes = true) },
)

internal data class AddArgs(
    val profile: String? = null,
    val name: String? = null,
    val baseUrl: String? = null,
    val models: List<String> = emptyList(),
    val command: String? = null,
    val live: Boolean = false,
    val yes: Boolean = false,
)

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

    fun usage(): Boolean {
        println(
            "usage: splice add <profile> [--name NAME] [--base-url URL] [--model ID]... [--command CMD] " +
                "[--live] [--yes]",
        )
        return false
    }
}
