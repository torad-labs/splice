package splice.observability.export;

import io.opentelemetry.api.common.Value;
import io.opentelemetry.sdk.logs.data.LogRecordData;

/** Keeps the SDK's required legacy body method at its Java compatibility boundary. */
interface ExportLogBody extends LogRecordData {
    // Override the SDK default so the legacy and modern getters cannot call each other.
    @Override
    Value<String> getBodyValue();

    /** @deprecated Use {@link #getBodyValue()}; required by the SDK 1.67 contract. */
    @Deprecated
    @Override
    default io.opentelemetry.sdk.logs.data.Body getBody() {
        return io.opentelemetry.sdk.logs.data.Body.string(getBodyValue().getValue());
    }
}
