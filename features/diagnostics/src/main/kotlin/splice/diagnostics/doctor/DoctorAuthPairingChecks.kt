// NEW: the doctor row for an auth kind its provider's dialect cannot carry, the pairing boot refuses.
package splice.diagnostics.doctor

import splice.core.topology.AuthCompatibility
import splice.core.topology.Topology
import java.nio.file.Path

/** One FAIL per head whose provider pairs an auth kind with a dialect that cannot carry it. The predicate is the one
 *  boot's findings and head assembly ask, so doctor, check-config and boot name the same heads. */
internal class DoctorAuthPairingChecks {
    fun checks(topology: Topology, configPath: Path): List<DoctorCheck> = topology.heads.mapNotNull { (key, head) ->
        topology.providers[head.provider]?.let { provider ->
            AuthCompatibility.refusal(key, head.provider, provider)?.let { why ->
                DoctorCheck(
                    "topology",
                    CheckStatus.FAIL,
                    why,
                    "change [providers.${head.provider}] auth.kind or dialect in $configPath, or point the head " +
                        "at another provider",
                )
            }
        }
    }
}
