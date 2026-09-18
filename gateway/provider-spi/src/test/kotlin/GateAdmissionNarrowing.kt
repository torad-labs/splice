// NEW: V4-114. The one narrowing the gate tests need, so a refusal can never be mistaken for a
// slot. InflightGate.acquire answers `Admission { Acquired | AtCapacity }` now instead of throwing
// GatewayAtCapacityException; these tests hold the limit themselves and a refusal here would be a
// bug in the test, so it fails loudly rather than being silently unwrapped. The REFUSAL arm is
// asserted on the value directly (InflightGateTest's maxQueued pins) — never through this helper.
import splice.spi.InflightGate

internal suspend fun InflightGate.admittedSlot(): InflightGate.Slot =
    when (val admission = acquire()) {
        is InflightGate.Admission.Acquired -> admission.slot
        InflightGate.Admission.AtCapacity ->
            error("the gate refused a slot this test holds capacity for: ${snapshot()}")
    }
