// Synthetic aggregate phase markers, never request content, credentials or runtime identities.
package splice.app.probe

internal class PlaygroundPhaseSamples {
    @Volatile var started = 0L

    @Volatile var routed = 0L

    @Volatile var built = 0L

    @Volatile var posted = 0L

    @Volatile var headers = 0L

    @Volatile var completed = 0L

    @Volatile var finished = 0L

    fun reset() {
        started = 0L
        routed = 0L
        built = 0L
        posted = 0L
        headers = 0L
        completed = 0L
        finished = 0L
    }

    fun print(label: String, readers: Int) {
        val stamps = listOf(started, routed, built, posted, headers, completed, finished)
        check(started > 0 && stamps.zipWithNext().all { (first, next) -> next >= first }) {
            "missing or reordered playground phase markers"
        }
        println(
            "playground_profile case=$label readers=$readers route_ns=${routed - started} " +
                "build_ns=${built - routed} post_ns=${posted - built} headers_ns=${headers - posted} " +
                "completion_ns=${completed - headers} write_ns=${finished - completed} " +
                "total_ns=${finished - started}",
        )
    }
}
