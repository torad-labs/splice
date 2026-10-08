// NEW: the probe readings of a daemon whose heads are all quiet, the one test double the control-server rigs and
// the ControlPlane.start rigs share. It implements the seam ControlPlane reads per request, so a rig that starts a
// daemon with no heads passes this rather than a lambda per reading.
package splice.app.control

import splice.app.head.HeadProbeReadings

internal object SilentHeadProbes : HeadProbeReadings {
    override fun stalledKeys(): List<String> = emptyList()

    override fun runtimeNotAnswering(): Map<String, String> = emptyMap()
}
