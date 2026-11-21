// NEW: gateway-no-heads. The owner of a sign-in splice keeps and refreshes, held once per persisted login however many
// routes serve it. A legacy head and the gateway's runtime for the same provider build their credentials from the same
// file, and two owners over one file would each refresh its rotating token, so one of them is told its refresh token
// was already spent. The owner is asked for by the file it keeps (and the kind of sign-in it is), above the dialect
// arms: the arm that builds a route asks for it and never makes one of its own.
package splice.app.provider

import splice.core.auth.RefreshableAuthProvider
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Builds the owner of a sign-in that has none yet. */
internal fun interface NewLogin {
    operator fun invoke(): RefreshableAuthProvider
}

internal class LoginOwners {
    private val owners = ConcurrentHashMap<String, RefreshableAuthProvider>()

    /** The owner of the [kind] sign-in kept in [file]: [build]'s, for the first route that asks, and the same one for
     *  every route after it. */
    fun held(kind: String, file: Path, build: NewLogin): RefreshableAuthProvider =
        owners.computeIfAbsent("$kind:${file.toAbsolutePath().normalize()}") { build() }
}
