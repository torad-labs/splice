// NEW: `splice wire`'s argument grammar, split from WireCommand.kt when that file entered concentration
// band HIGH (LAYOUT-01): `<head> [--last N] [--json]` is parsed before anything reaches the network.
package splice.diagnostics.wire

internal data class WireOpts(val head: String, val last: Int, val json: Boolean)

/** One head, an optional positive `--last`, an optional `--json`; anything else is null (refused). */
internal class WireArgs {

    fun parse(args: List<String>): WireOpts? {
        var head: String? = null
        var last = 0
        var json = false
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--json" -> json = true
                "--last" -> {
                    last = args.getOrNull(i + 1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                    i += 1
                }
                else -> if (head == null && !arg.startsWith("-")) head = arg else return null
            }
            i += 1
        }
        return head?.let { WireOpts(it, last, json) }
    }
}
