// NEW: DR-65 (codex security probe 2026-08-31) — one renderer for any failure whose exception
// text may quote the bytes of the file that produced it. kotlinx parse exceptions embed a
// "JSON input:" excerpt of the parsed input, so a bare `$failure` on a credential or state
// file's parse cause copied token/env bytes into daemon.log and /mgmt introspection.
package splice.core.util

public object SafeFailureText {

    /** Filesystem and network failures keep their full text — their messages are paths, hosts
     *  and timeouts, the useful safe diagnostics. Every other exception renders as a FIXED
     *  literal: parser messages can quote the input they failed on, and the class name is only
     *  reachable through overridable toString() (reflection is walled), so a throwable that
     *  overrides toString() colon-free would ride any prefix-taking render into diagnostics
     *  verbatim (codex probe, 2026-08-31). No virtual call happens outside the allowlist. */
    // SAFE-RENDER-EXEMPT[2026-09-01]: this IS the sanctioned renderer, and the allowlist below is the law's own definition of a throwable that cannot quote file bytes — a path, a host, a timeout. Routing it would recurse; the marker sits here so the allowlist carries its justification where DR-187 made it visible, rather than being the one render the wall structurally cannot ask about.
    public fun render(failure: Throwable): String = when (failure) {
        is java.nio.file.FileSystemException,
        is java.net.SocketException,
        is java.net.UnknownHostException,
        is java.io.InterruptedIOException,
        is java.io.EOFException,
        -> failure.toString()
        else -> "failure (message withheld — may quote file bytes)"
    }
}
