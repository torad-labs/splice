// NEW: the deadline a test gives a real worker when it asserts what a script DOES, never how fast. A test
// FIXTURE (LAYOUT-01) because this module's runtime tests and :app's two code-mode tests all start one.
//
// A cell's first exchange includes the worker JVM's own start: WorkerChannel.exchange times the whole
// round trip, write to reply. The production default (DEFAULT_ADVANCE_TIMEOUT_MS, 5 s) was measured on a
// quiet machine, and on a loaded CI runner the start alone went past it: run 36167036978, with the
// gate's projects building side by side, failed "code mode survives a head restart on the real runtime"
// at its FIRST script in 5.095 s (3.979 s for the whole test in the green run 36165795515). A test that
// asserts a DEADLINE passes its own small budget, never this one.
package splice.codemode

const val SCRIPT_DEADLINE_MS: Long = 30_000
