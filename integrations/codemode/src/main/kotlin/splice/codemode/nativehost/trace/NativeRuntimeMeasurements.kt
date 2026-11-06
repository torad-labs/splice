package splice.codemode.nativehost.trace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.codemode.nativehost.NativeCellAdmission
import splice.codemode.nativehost.NativeConnection

/** Projects one captured connection's owner readings without starting work or changing its lifetime. */
internal object NativeRuntimeMeasurements {
    fun snapshot(admission: NativeCellAdmission, current: NativeConnection?): JsonObject = buildJsonObject {
        put(
            "measurements",
            buildJsonObject {
                admission.measurements().forEach { (key, value) -> put(key, value) }
                current?.tracing?.invocations?.measurements()?.forEach { (key, value) -> put(key, value) }
                put("runtime_processes_active", if (current?.isAvailable() == true) 1 else 0)
            },
        )
    }
}
