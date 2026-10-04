// NEW: what `splice doctor` asks the process that runs it (LAYOUT-01). Doctor lives in
// features/diagnostics; two of its facts are only the executable's to answer — where the running jar
// is (core's RunningJar), and how a turn reaches a local runtime (the provider wiring's bearer and
// headers) — so app hands them in through ports. DoctorProbe moved here from app's CliPorts with
// the checks it isolates.
package splice.diagnostics.doctor

import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.upstream.transport.LocalHttp

/** The transport a turn would use to reach [provider]'s local runtime — its static headers and the
 *  bearer read under [key] the way a head reads it — so doctor asks exactly what a turn asks. */
public fun interface LocalRuntimeTransport {
    public fun http(key: String, provider: ProviderConfig, env: EnvReader): LocalHttp
}

/**
 * One doctor check group, run so that a crash inside it becomes a FAIL row rather than the end of
 * the report.
 *
 * Both halves matter and neither is optional: a crashing check must not kill the report, and it
 * must not masquerade as healthy either. The wrapper turns a throw into a `doctor` FAIL naming the
 * exception, which is why every check group goes through one of these.
 */
internal fun interface DoctorProbe {
    operator fun invoke(): List<DoctorCheck>
}
