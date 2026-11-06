package splice.usage.perf.query

internal object PerfCliArguments {
    fun read(args: List<String>): Map<String, String>? {
        val result = LinkedHashMap<String, String>()
        var index = 0
        while (index < args.size) {
            val flag = args[index]
            if (flag == "--json") {
                index++
                continue
            }
            if (!flag.startsWith("--") || index + 1 >= args.size) return null
            val key = flag.removePrefix("--").replace('-', '_')
            if (key in result) return null
            result[key] = args[index + 1]
            index += FLAG_AND_VALUE
        }
        return result
    }
}

// Each parsed CLI option consumes exactly two arguments: its flag and the following value.
private const val FLAG_AND_VALUE = 2
