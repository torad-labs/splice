// NEW: custom-provider (issue #680, cp-diagnostics). `splice doctor` names each provider whose key a command supplies, in
// its accounts section: signed in (until when), not signed in, or failed in the credential command's own words, with
// how to sign in; and under it, what `splice claude` says of that provider's heads: whether they are in /model and the
// last error the provider answered. It reads the daemon's own `splice claude` state (GET /api/claude), so both commands
// say the same thing.
package splice.diagnostics.doctor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.auth.COMMAND_STATE_FAILED
import splice.core.auth.COMMAND_STATE_HELD
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead

internal class DoctorCommandProviders(private val env: EnvReader) {
    private val lines = HeadLines()

    /** One check per provider on command auth in [topology], from the daemon's [heads]; none without any. */
    fun checks(topology: Topology?, heads: List<JsonObject>): List<DoctorCheck> {
        val providers = topology?.providers.orEmpty().filterValues { it.auth.isCommand }.keys
        if (providers.isEmpty()) return emptyList()
        return providers.flatMap { provider ->
            val own = heads.filter { JsonScalars.str(it, "provider") == provider }
            val head = topology?.heads?.entries?.firstOrNull { it.value.provider == provider }?.key ?: provider
            check(provider, head, own)
        }
    }

    /** The `heads` of `splice claude --json`, as the daemon answers them; empty when it does not answer. */
    fun heads(port: Int): List<JsonObject> {
        val key = (MgmtKeyFile().read(env) as? MgmtKeyRead.Present)?.key ?: return emptyList()
        val reply = ControlPlaneClient.send("http://127.0.0.1:$port/api/claude", "GET", key) ?: return emptyList()
        val state = Cancellables.runCatchingCancellable { Json.parseToJsonElement(reply.body) as? JsonObject }
            .fold(onSuccess = { it }, onFailure = { null })
        return (state?.get("heads") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    private fun check(provider: String, head: String, own: List<JsonObject>): List<DoctorCheck> {
        val name = "provider:$provider"
        val credential = own.firstNotNullOfOrNull { it["credential"] as? JsonObject }
        val login = "splice login $head"
        val (status, said, fix) = when (JsonScalars.str(credential, "state")) {
            COMMAND_STATE_HELD -> Triple(
                CheckStatus.OK,
                "$provider: ${lines.signedIn(JsonScalars.str(credential, "expires_at"))}",
                null,
            )
            COMMAND_STATE_FAILED -> Triple(
                CheckStatus.FAIL,
                "$provider: ${JsonScalars.str(credential, "error").orEmpty()}",
                "run $login, or /login in a claude session",
            )
            else -> Triple(CheckStatus.WARN, "$provider is not signed in", "run $login, or /login in a claude session")
        }
        val under = own.flatMap(::underLines).map { DoctorCheck(name, CheckStatus.INFO, it.trim()) }
        return listOf(DoctorCheck(name, status, said, fix)) + under
    }

    /** What `splice claude` says of one head of the provider, in short. */
    private fun underLines(head: JsonObject): List<String> = listOfNotNull(
        "  ${lines.inModel(head)}",
        lines.lastError(head)?.let { "  $it" },
    )
}
