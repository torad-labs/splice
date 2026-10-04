// NEW: V4-251 — the console a browser sign-in reads a pasted code from, as a seam: the process's own
// terminal in production, a fake one in a test that has to type the line the next prompt should get.
package splice.oauth

import java.io.InputStream

/** Where a browser sign-in reads a pasted redirect URL or code, racing the loopback callback. */
public interface PasteSource {
    /** True when a person is at a terminal, the only case a paste prompt is worth printing. */
    public fun interactive(): Boolean

    /** The terminal's bytes. The flow reads them only while it waits for its callback. */
    public fun input(): InputStream
}

/** The process's own terminal: interactive when the JVM has a console, read through System.in. */
public class SystemPasteSource : PasteSource {
    override fun interactive(): Boolean = System.console() != null

    override fun input(): InputStream = System.`in`
}
