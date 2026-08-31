// NEW (DR-65, codex security probe 2026-08-31): one renderer for any failure whose exception
// text may quote the bytes of the file that produced it. kotlinx parse exceptions embed a
// "JSON input:" excerpt of the parsed input, so a bare `$failure` on a credential or state
// file's parse cause copied token/env bytes into daemon.log and /mgmt introspection.
package splice.core.util

public object SafeFailureText {

    /** Filesystem and network failures keep their full text — their messages are paths, hosts
     *  and timeouts, the useful safe diagnostics. Every other exception renders as its class
     *  name alone: parser messages especially can quote the input they failed on. */
    public fun render(failure: Throwable): String = when (failure) {
        is java.nio.file.FileSystemException,
        is java.net.SocketException,
        is java.net.UnknownHostException,
        is java.io.InterruptedIOException,
        is java.io.EOFException,
        -> failure.toString()
        // Throwable.toString() is "classname: message" (or bare classname) — the prefix names
        // the type with no reflection; everything after the first colon is the withheld message.
        else -> "${failure.toString().substringBefore(":")} (message withheld — may quote file bytes)"
    }
}
