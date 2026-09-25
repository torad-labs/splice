// NEW: doctor's prerequisite-binary table — strings only, DoctorCheck is built on the miss
// path. Split from DoctorProbes.kt so that file is not billed for a constant catalogue
// (concentration HIGH, 2026-08-19): the table is data, the probe runner is behaviour.
package splice.diagnostics.doctor

internal const val FLAG_VERSION = "--version"

// V4-220 item 4: the public pages doctor's fixes link to, in the one list the report's redaction
// allows verbatim (DoctorRedaction). A URL's `//host/path` is path-shaped to that pass, so
// `install it: https://…` reached the console as `install it: https:<redacted:path>`.
private const val CLAUDE_CODE_DOCS = "https://docs.anthropic.com/en/docs/claude-code"
private const val NODE_DOWNLOAD = "https://nodejs.org"
internal val doctorLinks = listOf(CLAUDE_CODE_DOCS, NODE_DOWNLOAD)

// Strings only — DoctorCheck is built on the miss path, not pre-allocated for every binary.
internal data class BinarySpec(
    val name: String,
    val versionArgs: List<String>,
    val missingDetail: String,
    val fix: String,
)

// FILE SCOPE ON PURPOSE: the probe table is a constant shared by every doctor run.
internal val binaries = listOf(
    BinarySpec(
        "claude",
        listOf(FLAG_VERSION),
        "Claude Code not found on PATH — splice wraps it",
        "install it: $CLAUDE_CODE_DOCS",
    ),
    BinarySpec(
        "node",
        listOf("-v"),
        "not found on PATH — Claude Code's runtime and the launch shim's (Node 24)",
        "install Node 24: $NODE_DOWNLOAD",
    ),
    BinarySpec(
        "curl",
        listOf(FLAG_VERSION),
        "not found on PATH — install.sh downloads releases with it",
        "install curl with your package manager",
    ),
    BinarySpec(
        "bash",
        listOf(FLAG_VERSION),
        "not found on PATH — install.sh is a bash script",
        "install bash with your package manager",
    ),
)
