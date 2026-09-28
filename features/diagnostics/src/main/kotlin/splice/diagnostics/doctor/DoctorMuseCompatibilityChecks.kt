// NEW: warn once per Muse provider when pre-Responses topology still relies on boot compatibility.
package splice.diagnostics.doctor

import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.Topology
import java.nio.file.Path

internal class DoctorMuseCompatibilityChecks {
    fun checks(topology: Topology, configPath: Path): List<DoctorCheck> {
        val muse = topology.providers["muse"]
            ?.takeIf { it.auth.kind == AuthKind.MuseOAuth.wire } ?: return emptyList()
        val base = muse.baseUrl.trimEnd('/')
        return buildList {
            if (muse.dialect == Dialect.ANTHROPIC_PASSTHROUGH) {
                add(
                    DoctorCheck(
                        "muse-dialect:muse",
                        CheckStatus.WARN,
                        "provider 'muse' declares stale dialect = \"anthropic-passthrough\"; " +
                            "splice serves it through Responses on the next start",
                        "set dialect = \"openai-responses\" in [providers.muse] of $configPath",
                    ),
                )
            }
            if (!base.endsWith("/v1")) {
                val fix = "append /v1 to [providers.muse].base_url in $configPath"
                add(
                    DoctorCheck(
                        "muse-base-url:muse",
                        CheckStatus.WARN,
                        "provider 'muse' declares stale base_url without /v1; " +
                            "splice appends /v1 before sending Responses requests",
                        fix,
                    ),
                )
            }
        }
    }
}
