// NEW: doctor's prerequisite-binary table — strings only, DoctorCheck is built on the miss
// path. Split from DoctorProbes.kt so that file is not billed for a constant catalogue
// (concentration HIGH, 2026-08-19): the table is data, the probe runner is behaviour.
package splice.app.cli

internal const val FLAG_VERSION = "--version"

// Strings only — DoctorCheck is built on the miss path, not pre-allocated for every binary.
internal data class BinarySpec(
    val name: String,
    val versionArgs: List<String>,
    val missingDetail: String,
    val fix: String,
)

// FILE SCOPE ON PURPOSE: the probe table is a constant shared by every doctor run.
internal val BINARIES = listOf(
    BinarySpec(
        "claude",
        listOf(FLAG_VERSION),
        "Claude Code not found on PATH — splice wraps it",
        "install it: https://docs.anthropic.com/en/docs/claude-code",
    ),
    BinarySpec(
        "node",
        listOf("-v"),
        "not found on PATH — Claude Code's runtime (Node 24)",
        "install Node 24: https://nodejs.org",
    ),
    BinarySpec(
        "python3",
        listOf(FLAG_VERSION),
        "not found on PATH — the launch shim parses JSON with it",
        "install python3 with your package manager",
    ),
    BinarySpec(
        "curl",
        listOf(FLAG_VERSION),
        "not found on PATH — the launch shim's health checks need it",
        "install curl with your package manager",
    ),
    BinarySpec(
        "bash",
        listOf(FLAG_VERSION),
        "not found on PATH — the launch shim is a bash script",
        "install bash with your package manager",
    ),
)
