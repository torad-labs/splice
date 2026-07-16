// PORT-OF: server/src/versions.mjs @ 4ca99f7 — the /health version string is the ENTIRE
// staleness-detection mechanism (no PID/lock files; the launcher compares this to a live
// /health). Single daemon (P4): one version restarts every head together (documented change).
package splice.core

public const val GATEWAY_VERSION: String = "kt-1"
