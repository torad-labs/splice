// NEW: desktop-policy-console. What the console's Desktop policy routes reach of the daemon's Claude Desktop part
// (DaemonDesktop): splice's policy layer by layer with the console's values among them, a publication of those values
// to every Desktop on the door, and whether Desktop has yet to read the last one.
package splice.app.desktop.setup

internal interface DesktopPolicyTarget {
    /** splice's policy for Desktop, layer by layer; its overrides are the console's values. */
    val policyLayers: DesktopPolicyLayers

    /** The console's values given to every Desktop on the door, each key of [reset] put back to splice's policy; how
     *  many configurations changed, or null while the door is not open. */
    suspend fun publishPolicy(reset: Set<String>): Int?

    /** Whether Desktop has not been reopened since splice last changed its configuration; null when that can't be
     *  told. */
    fun restartPending(): Boolean?
}
