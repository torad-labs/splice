package splice.observability.export;

import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.SpanData;

/** Keeps the SDK's required legacy scope method at its Java compatibility boundary. */
interface ExportSpanScope extends SpanData {
    // Override the SDK default so the legacy and modern getters cannot call each other.
    @Override
    InstrumentationScopeInfo getInstrumentationScopeInfo();

    /** @deprecated Use {@link #getInstrumentationScopeInfo()}; required by the SDK 1.67 contract. */
    @Deprecated
    @Override
    default io.opentelemetry.sdk.common.InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
        InstrumentationScopeInfo scope = getInstrumentationScopeInfo();
        return io.opentelemetry.sdk.common.InstrumentationLibraryInfo.create(
            scope.getName(), scope.getVersion(), scope.getSchemaUrl());
    }
}
