// PORT-OF: daemon/control/.../ControlPorts.kt (DoctorReport) — invariants unchanged: the doctor
// route's port, moved beside the route that answers with it.
package splice.diagnostics.doctor

/**
 * V4-127: the `doctor --json` report, as the CLI renders it (JSON TEXT, not a JsonObject, so these
 * ports stay free of a serialization import and the daemon decides the shape in the module that
 * already owns it).
 *
 * `UpgradeStatus` (the lifecycle feature) returns the same `() -> String`, and the control plane's
 * `MgmtRoute` is a `() -> Unit` shape — three questions that a raw function type could not tell apart. Naming the question is
 * what keeps a caller from wiring the upgrade payload into the doctor route and having both compile.
 */
public fun interface DoctorReport {
    public operator fun invoke(): String
}
