// NEW (Oct 29, 2026): a JVM that unpacks the bundled code-mode host and then ends without closing it, as the daemon
// does on every restart and a test JVM does when it exits.
package splice.codemode.nativehost

internal object NativeHostExitProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val host = NativeHostArtifact().resolve()
        println(host)
        System.out.flush()
        // "wait" holds the JVM until it is signalled, as a running daemon is; anything else ends it at once.
        if (args.firstOrNull() == "wait") System.`in`.read()
    }
}
