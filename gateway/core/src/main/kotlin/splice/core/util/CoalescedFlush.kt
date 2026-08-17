// NEW: split from splice.gateway.usage.UsageStore (HD-24, 2026-08-17) — a CAS debounce over
// AsyncFileIo.submit with a FileIoTask and an AtomicBoolean, both of which already live here;
// it carries zero usage vocabulary, so it sits beside the primitives it wraps rather than in the
// module that happened to be its first caller. The 1s usage delay is now a parameter.
package splice.core.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * CAS-guarded debounce: [flag] gates a single in-flight [flush] submission, scheduled [delayMs]
 * out on the bounded file-I/O lane. Rolling [flag] back when [AsyncFileIo.submit] rejects is the
 * load-bearing subtlety here — a missed rollback wedges the lane forever (review 2026-07-22
 * round 3, originally in splice.gateway.usage.UsageStore).
 */
public object CoalescedFlush {
    public fun scheduleCoalesced(delayMs: Long, flag: AtomicBoolean, flush: FileIoTask) {
        if (!flag.compareAndSet(false, true)) return
        if (!AsyncFileIo.submit(delayMs, flush)) {
            flag.set(false)
        }
    }
}
