// The synthetic head the Playground tests assemble: a real ManagedHead over a temp state folder, one provider, and the
// registry the probe sends through, so a test drives the same seams production does.
package splice.app.probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.head.HeadServerFactory
import splice.app.head.HeadServing
import splice.app.head.LaunchSpecFactory
import splice.app.head.ManagedHeadFactory
import splice.app.head.QuotaPollSeams
import splice.app.head.StartQuotaPoller
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.UpstreamFaultPlan
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

internal class PlaygroundHeadFixture(private val root: Path) {
    fun login(account: String): kotlinx.serialization.json.JsonObject {
        val encoded = Base64.getUrlEncoder().withoutPadding()
        val header = encoded.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = encoded.encodeToString("""{"exp":4102444800}""".toByteArray())
        return buildJsonObject {
            putJsonObject("tokens") {
                put("access_token", "$header.$payload.synthetic")
                put("refresh_token", "synthetic")
                put("account_id", account)
            }
        }
    }

    fun build(
        scope: CoroutineScope,
        keyName: String = "claudex",
        authKind: String = "chatgpt-oauth",
    ): Pair<splice.app.control.ManagedHead, PlaygroundProviders> {
        val primary = root.resolve("absent-auth.json")
        if (!java.nio.file.Files.exists(primary)) SecureFile.writeAtomic0600(primary, login("primary").toString())
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val config = ConfigService(paths)
        val key = MgmtKey(paths)
        val planner = SignInPlanner()
        val registry = PlaygroundProviders()
        val factory = ManagedHeadFactory(
            statePaths = paths,
            providerAssembly = ProviderAssembly(
                paths,
                scope,
                {},
                TokenUrlRefreshCall { _, _ -> error("synthetic assembly must not refresh") },
            ),
            serving = HeadServing(HeadServerFactory(config, key, {}), registry),
            launchSpecFactory = LaunchSpecFactory(
                Topology(),
                planner,
                key,
                HeadBuildInputs(config, planner),
            ),
            log = {},
            quotaSeams = QuotaPollSeams(
                scope,
                {},
                startQuotaPoller = StartQuotaPoller { _, _, _, _ -> null },
            ),
        )
        val model = ModelEntry("synthetic-model", contextWindow = 4_000)
        val kimi = authKind == "kimi-oauth"
        val head = HeadConfig(if (kimi) "kimi" else "synthetic", 3099, "synthetic--", model.id)
        val provider = ProviderConfig(
            dialect = if (kimi) Dialect.ANTHROPIC_PASSTHROUGH else Dialect.OPENAI_RESPONSES,
            baseUrl = "https://synthetic.example/responses",
            auth = AuthConfig(authKind, file = root.resolve("absent-auth.json").toString()),
        )
        val build = ProviderBuild(
            key = keyName,
            head = head,
            providerCfg = provider,
            catalog = ModelCatalog("synthetic--", listOf(model), defaultContextWindow = model.contextWindow),
            faultPlan = UpstreamFaultPlan(WatchdogBudget(60.seconds, 60.seconds, 600.seconds), "synthetic login"),
            cfg = config.getConfig(keyName),
        )
        return factory.assembleHead(build, 3098) to registry
    }
}
