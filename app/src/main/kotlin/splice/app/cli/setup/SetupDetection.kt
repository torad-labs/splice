// NEW: detect-then-preselect facts for splice setup (cli-wizard CW-6).
package splice.app.cli.setup

import splice.app.cli.add.DaemonUpProbe
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.util.EnvReader

/** Presence of a credential file: exists and non-empty. Never reads a byte of the file. */
internal fun interface CredentialPresenceProbe {
    operator fun invoke(path: String): Boolean
}

/** Effective control port. Production wires [AdminSupport.controlPort] so topology, state and env layer. */
internal fun interface ControlPortResolver {
    operator fun invoke(env: EnvReader): Int
}

internal data class SetupFacts(
    val spliceOwned: Set<String>,
    val vendorCli: Set<String>,
    val openRouterKey: Boolean,
    val daemonUp: Boolean,
    val suggested: SetupStart,
)

internal sealed class SetupStart {
    data object OpenRouter : SetupStart()
    data class OAuth(val kind: String) : SetupStart()
    data object Existing : SetupStart()
}

internal class SetupDetection(
    private val env: EnvReader,
    private val credentials: CredentialPresenceProbe,
    private val daemon: DaemonUpProbe,
    private val controlPort: ControlPortResolver = ControlPortResolver { AdminSupport.controlPort(it) },
) {
    fun detect(): SetupFacts {
        val owned = presentOwned()
        val vendor = presentVendor()
        val openRouter = !env(OPENROUTER_KEY).isNullOrBlank()
        val daemonUp = daemon(controlPort(env))
        return SetupFacts(owned, vendor, openRouter, daemonUp, suggest(owned, vendor, openRouter, daemonUp))
    }

    private fun presentOwned(): Set<String> =
        AuthKindRegistry.knownKinds().mapNotNull { kind ->
            kind.defaultAuthFile?.let { path -> kind.wire.takeIf { credentials(path) } }
        }.toSet()

    private fun presentVendor(): Set<String> =
        AuthKindRegistry.knownKinds().mapNotNull { kind ->
            val oauth = kind as? AuthKind.OAuth ?: return@mapNotNull null
            oauth.wire.takeIf { credentials(oauth.nativeAppFile) }
        }.toSet()

    private fun suggest(
        owned: Set<String>,
        vendor: Set<String>,
        openRouter: Boolean,
        daemonUp: Boolean,
    ): SetupStart {
        val anyCredential = owned.isNotEmpty() || vendor.isNotEmpty() || openRouter
        if (daemonUp && anyCredential) return SetupStart.Existing
        val kind = AuthKindRegistry.knownKinds().firstOrNull { it.wire in owned || it.wire in vendor }
        return if (kind != null) SetupStart.OAuth(kind.wire) else SetupStart.OpenRouter
    }
}

private const val OPENROUTER_KEY = "OPENROUTER_API_KEY"
