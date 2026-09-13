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
    fun parse(args: List<String>): AddArgs {
        var parsed = AddArgs()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val v = args.getOrNull(i + 1).orEmpty()
            val valued = VALUED_FLAGS[a]
            parsed = when {
                valued != null -> valued(parsed, v).also { i++ }
                a == "--live" -> parsed.copy(live = true)
                a == "--yes" || a == "-y" -> parsed.copy(yes = true)
                else -> parsed.copy(profile = parsed.profile ?: a)
            }
            i++
        }
        return parsed
    }
}
