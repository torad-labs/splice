package splice.observability

// OTLP timestamp fields use nanoseconds while Splice's observation and aggregate clocks use milliseconds.
internal const val OBSERVABILITY_NANOS_PER_MILLISECOND = 1_000_000L

// Exported seconds and runtime per-second rates share this conversion from millisecond observation windows.
internal const val OBSERVABILITY_MILLIS_PER_SECOND = 1_000.0
