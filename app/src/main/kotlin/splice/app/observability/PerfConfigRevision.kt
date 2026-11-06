package splice.app.observability

import splice.core.config.Knob
import splice.core.config.KnobKind
import java.security.MessageDigest
import java.util.HexFormat

/** Effective knob policy identity. Credential locations, headers and endpoints never enter a digest. */
internal object PerfConfigRevision {
    fun project(values: Map<String, Any?>): Map<String, Any?> = Knob.entries.filter { it.key !in EXCLUDED }.associate {
        val value = values[it.key]
        val safe = it.kind != KnobKind.STRING || value == null || it.key in POLICIES && policyText.matches(
            value.toString(),
        )
        it.key to if (safe) value else UNKNOWN_POLICY
    }

    fun of(values: Map<String, Any?>): String? {
        val policy = project(values)
        if (policy.values.any { it == UNKNOWN_POLICY }) return null
        val bytes = policy.entries
            .sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    fun effective(boot: Map<String, Any?>, desired: Map<String, Any?>): Map<String, Any?> =
        desired + Knob.entries.filter { it.restartRequired }.associate { it.key to boot[it.key] }
}

private const val UNKNOWN_POLICY = "!unsupported_policy"
private val policyText = Regex("[A-Za-z0-9_.,*/: -]{0,256}")
private val POLICIES = setOf(
    "pinnedModel", "grokModel", "effort", "verbosity", "summary", "showReasoning",
    "toolSurface", "quotaPoll",
    "budgetDefaultAction", "historyRetentionDays", "telemetryOtlpProtocol",
    "telemetryOtlpSignals", "activityStoreHeads",
    "supervisorUnit", "mcpSlice",
)
private val EXCLUDED = setOf(
    "codexAuthPath",
    "grokAuthPath",
    "telemetryOtlpHeaders",
    "telemetryOtlpEndpoint",
    "chatgptApiBase",
    "xaiApiBase",
    "statuslineGitRoots",
    "projectsFolders",
)
