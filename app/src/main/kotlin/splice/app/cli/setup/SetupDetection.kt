// NEW: detect-then-preselect facts for splice setup (cli-wizard CW-6).
package splice.app.cli.setup

import splice.app.capability.Capabilities
import splice.app.capability.CapabilityLook
import splice.app.cli.AdminSupport
import splice.configuration.add.DaemonUpProbe
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.util.Cancellables
import splice.core.util.EnvReader

/** Presence of a credential file: exists and non-empty. Never reads a byte of the file. */
internal fun interface CredentialPresenceProbe {
    operator fun invoke(path: String): Boolean
}

/** Effective control port. Production wires [AdminSupport.controlPort] so topology, state and env layer. */
internal fun interface ControlPortResolver {
    operator fun invoke(env: EnvReader): Int
}

/** The optional programs splice works beside, read once (CapabilityLook): Claude Desktop and its mode, agy, codex,
 *  and Claude Code's managed settings for the Code tab. */
internal fun interface CapabilityProbe {
    operator fun invoke(env: EnvReader): Capabilities?
}

/** [capabilities] is null where nothing read them (a test's facts). */
internal data class SetupFacts(
    val spliceOwned: Set<String>,
    val vendorCli: Set<String>,
    val openRouterKey: Boolean,
    val daemonUp: Boolean,
    val capabilities: Capabilities? = null,
)

internal class SetupDetection(
    private val env: EnvReader,
    private val credentials: CredentialPresenceProbe,
    private val daemon: DaemonUpProbe,
    private val controlPort: ControlPortResolver = ControlPortResolver { AdminSupport.controlPort(it) },
    private val look: CapabilityProbe = CapabilityProbe {
        Cancellables.runCatchingBestEffort { CapabilityLook(it).read() }.getOrNull()
    },
) {
    fun detect(): SetupFacts {
        val owned = presentOwned()
        val vendor = presentVendor()
        val openRouter = !env(OPENROUTER_KEY).isNullOrBlank()
        val daemonUp = daemon(controlPort(env))
        return SetupFacts(owned, vendor, openRouter, daemonUp, look(env))
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
}

private const val OPENROUTER_KEY = "OPENROUTER_API_KEY"
