// NEW: what the control plane listens on — its port, and the already-listening socket a manager handed over for it
// when there is one. Two ControlServer parameters would be one too many: that constructor is at the width ceiling
// of 7, and the two are one fact anyway, since the port is where the server listens and the socket is how.
package splice.app.control

import splice.http.listen.AdoptedBootstrap

/** [adopted] null = the server binds [port] itself, which is every start without socket activation. */
internal data class ControlListen(
    val port: Int,
    val adopted: AdoptedBootstrap? = null,
)
