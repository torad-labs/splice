// NEW: LAYOUT-01 — the launch feature's test rig: fixed heads behind the LaunchHeads port, keyed the
// way the control plane keys them, so each route test builds only the head it is about.
package splice.launch

import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.launch.recipe.LaunchCaller
import splice.launch.recipe.LaunchService

internal fun launchHeadsOf(vararg heads: LaunchHead): LaunchHeads = object : LaunchHeads {
    override fun all(): List<LaunchHead> = heads.toList()

    override fun byKey(key: String): LaunchHead? = heads.firstOrNull { it.head.key == key }

    override fun targets(name: String): List<LaunchHead> =
        heads.filter { (it.head.key == name || it.head.label == name) && it.spec != null }
}

/** A running head that does nothing; the launch surfaces read only its key, label and health. */
internal fun runningHead(key: String): Head = object : Head {
    override val key: String = key
    override val label: String = key
    override val port: Int = 0
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
}

/** An auth provider holding no credential, described as [kind]. */
internal fun absentAuth(kind: String): AuthProvider = object : AuthProvider {
    override suspend fun credentials() = null
    override suspend fun describe() = AuthDescription(false, kind, emptyMap())
}

/** The recipe of a launch no wrap record refused. A refusal fails the test with its reason, so the tests that read
 *  `.env` and `.argv` keep their call sites while production acts on [LaunchOutcome] at its one route. */
internal fun LaunchService.launch(
    spec: LaunchSpec,
    extraArgs: List<String>,
    dangerouslySkipPermissions: Boolean,
    keyPresentNow: Boolean = true,
    caller: LaunchCaller = LaunchCaller(),
): LaunchRecipe = when (
    val outcome = launchOutcome(spec, extraArgs, dangerouslySkipPermissions, keyPresentNow, caller)
) {
    is LaunchOutcome.Ready -> outcome.recipe
    is LaunchOutcome.Refused -> error("launch refused: ${outcome.reason}")
}
