// NEW: terminal launch profiles (issue #791, comment §4). `splice launch claude [--profile <name>] [args...]`: the
// side-by-side launch on the door, in the profile's folder when `--profile` names one. `--profile <name>` and
// `--profile=<name>` are splice's own words, taken out before the rest goes to Claude Code, which has no such option;
// a `--` ends splice's words, so everything after it is Claude Code's as written.
package splice.launch.recipe

internal class SideBySideLaunch(private val door: ClaudeDoor) {
    /** What runs for [args]: [plain], the door's side-by-side launch, or the profile's, with the arguments Claude Code
     *  is given; or why nothing runs. [inheritedConfigDir], the launching shell's CLAUDE_CONFIG_DIR, goes to the
     *  profile's rebuild. */
    suspend fun start(plain: DoorLaunchSpec, args: List<String>, inheritedConfigDir: String? = null): Started {
        val split = split(args)
        split.problem?.let { return Started.Refused(it) }
        val name = split.profile ?: return Started.Ready(plain, split.rest)
        return when (val launch = door.launchProfile(name, plain, inheritedConfigDir)) {
            is ProfileLaunch.Ready -> Started.Ready(launch.spec, split.rest)
            is ProfileLaunch.Refused -> Started.Refused(launch.reason)
        }
    }

    /** [args] with `--profile` taken out, before any `--`. */
    fun split(args: List<String>): Split {
        val end = args.indexOf(PROFILE_ARGS_END).takeIf { it >= 0 } ?: args.size
        val words = args.subList(0, end)
        val named = mutableListOf<String?>()
        val rest = mutableListOf<String>()
        var index = 0
        while (index < words.size) {
            val word = words[index]
            val taken = named(word, words.getOrNull(index + 1))
            if (taken == null) rest += word else named += taken.name
            index += taken?.width ?: 1
        }
        val problem = when {
            named.any { it.isNullOrBlank() } -> NEEDS_NAME
            named.size > 1 -> TWICE
            else -> null
        }
        if (problem != null) return Split(null, args, problem)
        return Split(named.singleOrNull(), rest + args.subList(end, args.size), null)
    }

    /** [word] as `--profile`, with the name it gives ([next] for the spelled-apart form) and the words it takes up;
     *  null for any other word. */
    private fun named(word: String, next: String?): Named? = when {
        word == PROFILE -> next?.takeUnless { it.startsWith("-") }.let { Named(it, if (it == null) 1 else 2) }
        word.startsWith("$PROFILE=") -> Named(word.substringAfter('='), 1)
        else -> null
    }

    private data class Named(val name: String?, val width: Int)

    /** [profile] named, the [rest] for Claude Code, or the [problem] with the words. */
    data class Split(val profile: String?, val rest: List<String>, val problem: String?)

    sealed class Started {
        data class Ready(val spec: DoorLaunchSpec, val args: List<String>) : Started()

        data class Refused(val reason: String) : Started()
    }
}

private const val PROFILE = "--profile"
private const val PROFILE_ARGS_END = "--"
private const val NEEDS_NAME =
    "splice: --profile needs a profile's name: --profile <name>, a [launch.profiles.<name>] in splice.toml"
private const val TWICE = "splice: --profile is given twice; name one profile"
