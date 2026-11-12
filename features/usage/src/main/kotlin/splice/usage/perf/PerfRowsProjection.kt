// NEW: complete usage/filter facts with bounded materialization of displayed perf rows.
package splice.usage.perf

/** Optional read capability; [PerfRowsSource.window] and all of its consumers remain unchanged.
 * The callback holds one coherent source read. Its window contains every matching-time row's
 * timestamp, attribution, flags, token presence and all [splice.core.model.TurnBill] inputs.
 * Other numeric fields and trace identifiers are available through [PerfRowsProjection.complete]. */
public interface ProjectedPerfRowsSource {
    public fun <T> projected(sinceMs: Long, read: PerfProjectionRead<T>): T
}

/** One consumer's selection, aggregation and rendering inside a coherent projected source read.
 * The source may retry this callback if deferred rows change. Publish only after it returns. */
public fun interface PerfProjectionRead<T> {
    public operator fun invoke(projection: PerfRowsProjection): T
}

/** Reader evidence and append-ordered usage facts are independent of the displayed row limit. */
public interface PerfRowsProjection {
    public val window: PerfRowsWindow

    /** Complete original rows, in the selected order, including every numeric and descriptive fact. */
    public fun complete(rows: List<PerfRow>): List<PerfRow>
}
