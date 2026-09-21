// NEW: V4-114. The head tests build a TurnDrive around a real InflightGate slot; acquire() answers
// `Admission { Acquired | AtCapacity }` now instead of throwing GatewayAtCapacityException, so the
// narrowing lives in one place. These call sites hold the whole limit, so a refusal is a bug in the
// test and fails loudly. The refusal ARM is pinned on the value in :upstream's InflightGateTest
// and on the wire in AdmissionGateTest — never through this helper.
package head

import splice.upstream.retry.InflightGate

internal suspend fun InflightGate.admittedSlot(): InflightGate.Slot =
    when (val admission = acquire()) {
        is InflightGate.Admission.Acquired -> admission.slot
        InflightGate.Admission.AtCapacity ->
            error("the gate refused a slot this test holds capacity for: ${snapshot()}")
    }
