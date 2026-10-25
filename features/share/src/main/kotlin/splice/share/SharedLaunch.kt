// NEW: LAN share (docs/specs/lan-share.md). What a command another machine runs needs from this one to launch Claude
// Code: the head's models as a session here gets them (picker, tiers and pinned model), its client timeout, and where
// its logins are signed in. No credential, token or file is in it. The other machine's address chooses with two
// params, and this machine applies them here: `auth=` must name one of the head's logins, and `models=` narrows every
// part of the models to the ones it lists. A login or a model the head lacks is refused, naming what it has, so a typo
// is said when the command launches, never found out as a session on the wrong login or model.
package splice.share

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.launch.LaunchModels
import splice.launch.LaunchSpec
import splice.launch.ModelTiers
import splice.launch.PICKER_VALUE

/** One command's launch, as this machine hands it to another: the [machine] it runs on, the [command] and [head] it
 *  is there, its [models], Claude Code's request timeout for it, and where its logins are signed in. [clientModels]
 *  is a head whose client picks its own models (a Claude head, V4-449): the other machine's launch then plants none of
 *  [models] over the person's own settings, as a session here gets none. */
@Serializable
internal data class SharedLaunch(
    val machine: String,
    val command: String,
    val head: String,
    val models: SharedModels,
    val timeoutMs: Long,
    val signIn: SharedSignIn,
    val clientModels: Boolean = false,
)

/** LaunchModels on the wire, part for part. */
@Serializable
internal data class SharedModels(
    val pinned: String,
    val offered: List<String>,
    val labels: Map<String, String>,
    val window: Long,
    val picker: JsonElement,
    val byTier: Map<String, String> = emptyMap(),
    val candidates: List<String>? = null,
    val overrides: Map<String, String> = emptyMap(),
    val spelled: Map<String, String> = emptyMap(),
    val discoveryPrefix: String = "",
) {
    /** These models as the other machine's launch takes them. */
    fun launchModels(): LaunchModels = LaunchModels(
        pinned,
        offered,
        labels,
        window,
        picker,
        ModelTiers(byTier, candidates, overrides, spelled),
        discoveryPrefix,
    )
}

/** The [command] that signs the head in on its machine (`claudex login`), and the sign-in's [label]. */
@Serializable
internal data class SharedSignIn(val command: String, val label: String)

/** What asking for a launch came to: the launch, or the [status] and [why] it was refused with. */
internal sealed class SharedLaunchRead {
    data class Read(val launch: SharedLaunch) : SharedLaunchRead()

    data class Refused(val status: Int, val why: String) : SharedLaunchRead()
}

internal class SharedLaunches(private val machine: String) {
    /** [head]'s launch for an address that named login [auth] and models [models] (`a,b`), each when it did. */
    fun of(head: SharedHead, auth: String?, models: String?): SharedLaunchRead {
        val spec = head.launch()
            ?: return SharedLaunchRead.Refused(HttpStatus.NOT_FOUND, "${head.command} on $machine launches nothing")
        val wanted = models?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty().distinct()
        val why = auth?.takeIf { it !in head.logins() }?.let { noLogin(head, it, head.logins()) }
            ?: wanted.firstOrNull { it !in spec.models.availableModelIds }?.let { noModel(head, it, spec) }
        if (why != null) return SharedLaunchRead.Refused(HttpStatus.BAD_REQUEST, why)
        val shown = if (wanted.isEmpty()) spec.models else narrowed(spec.models, wanted.toSet())
        val signIn = SharedSignIn(spec.signIn.loginCommand, spec.signIn.signInLabel)
        val gateway = spec.gateway
        val launch = SharedLaunch(
            machine,
            head.command,
            head.key,
            wire(shown),
            gateway.apiTimeoutMs,
            signIn,
            gateway.clientModels,
        )
        return SharedLaunchRead.Read(launch)
    }

    /** What an address naming [login], which [head] lacks, is told. */
    fun noLogin(head: SharedHead, login: String, logins: List<String>): String {
        val has = logins.joinToString(", ").ifEmpty { "none" }
        return "${head.command} on $machine has no login named '$login'; it has $has"
    }

    private fun noModel(head: SharedHead, model: String, spec: LaunchSpec): String {
        val has = spec.models.availableModelIds.joinToString(", ")
        return "${head.command} on $machine has no model named '$model'; it has $has"
    }

    /** [models] with every part that names a model kept to [keep]: the offered list, labels, picker rows, tier slots,
     *  tier candidates, presented overrides and client spellings. A pinned model outside it gives way to the first
     *  one kept, so the session opens on a model the address asked for. */
    private fun narrowed(models: LaunchModels, keep: Set<String>): LaunchModels {
        val offered = models.availableModelIds.filter(keep::contains)
        val tiers = models.tiers
        val picker = (models.modelOptionsCache as? JsonArray)?.let { rows ->
            JsonArray(rows.filter { row -> JsonScalars.str(row as? JsonObject, PICKER_VALUE) in keep })
        }
        return models.copy(
            pinnedModel = models.pinnedModel.takeIf(keep::contains) ?: offered.first(),
            availableModelIds = offered,
            modelLabels = models.modelLabels.filterKeys(keep::contains),
            modelOptionsCache = picker ?: models.modelOptionsCache,
            tiers = tiers.copy(
                byTier = tiers.byTier.filterValues(keep::contains),
                candidates = tiers.candidates?.filter(keep::contains),
                modelOverrides = tiers.modelOverrides.filterValues(keep::contains),
                spelled = tiers.spelled.filterKeys(keep::contains),
            ),
        )
    }

    private fun wire(models: LaunchModels): SharedModels = SharedModels(
        models.pinnedModel,
        models.availableModelIds,
        models.modelLabels,
        models.contextWindow,
        models.modelOptionsCache,
        models.tiers.byTier,
        models.tiers.candidates,
        models.tiers.modelOverrides,
        models.tiers.spelled,
        models.discoveryPrefix,
    )
}
