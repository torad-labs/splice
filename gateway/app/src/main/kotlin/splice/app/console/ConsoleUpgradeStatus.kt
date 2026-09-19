// NEW: V4-127, FEATURES.md §6 — the body of the ControlServer's UpgradeStatus port.
//
// A VALUE PLUS A BASIS, NEVER A BARE BOOLEAN. Two of the three facts can be absent for two different
// reasons, and a payload that collapses them answers "you are up to date" when nothing ever checked.
// So each fact carries `latest`/`rollback_target` (the value, nullable), a `_basis` of measured or
// unavailable, and a `_unavailable_reason` that says why when the basis is unavailable. Measured with
// a NULL value is a real answer — "we looked and there is nothing newer", "no previous release
// exists" — and unavailable is "we could not look". The console's own Figure and StripField already
// take a basis and print it whenever it is anything but measured, so the reason string lands in a slot
// that already exists rather than needing one invented for it.
//
// CHECKED_AT_EPOCH_MILLIS IS ABSOLUTE, NEVER AN AGE. A relative age is computed when the payload is
// built and is wrong the moment it is cached, replayed, or written into a fixture — and this console's
// fixtures are files read back months later. null means the check has NEVER SUCCEEDED: not zero, and
// not the epoch, either of which would date the absence to 1970 and read as a real measurement.
//
// THE ROUTE NEVER FETCHES, AND THAT IS A RULED DECISION RATHER THAN A MISSING FEATURE. A polled GET
// that reached the network would hold a Ktor worker behind a 300-second timeout and aim the daemon's
// own request pool at a remote host on every poll — a self-inflicted outage wearing a feature. The
// shape to copy is QuotaPoller's, and it is worth naming because someone WILL try to simplify it away:
// "A failing endpoint is logged once, then silence until it recovers — the bars simply keep the last
// snapshot." A poll that cannot look keeps showing what it last knew, and says when that was. This
// body reads only local state today; when a check writes a snapshot, this reports the snapshot and its
// checked_at, and never performs the check itself.
//
// WHY `latest` IS UNAVAILABLE RATHER THAN GUESSED: reaching it through the machinery that exists is not
// a probe. UpgradeRelease.stage downloads the jar and the shim, checks every asset's sha256 against the
// published sums, attests the lot through an authenticated `gh`, and then RUNS the candidate jar to
// read its version. UpgradeLayout records no last-checked version and UpgradeFetch returns a body
// without the final URL a `releases/latest/download` redirect would carry the tag in, so there is no
// cheaper path in the tree. The field says so instead of inventing one.
package splice.app.console

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.UpgradeLayout
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText

/** The two bases a fact may rest on. A LABEL the console prints when it is not `measured`. */
internal const val BASIS_MEASURED = "measured"
internal const val BASIS_UNAVAILABLE = "unavailable"

/** Why `latest` is unavailable: not a failure, an absence of any check at all. */
internal const val LATEST_NOT_CHECKED =
    "no upgrade check has succeeded on this daemon; splice learns the latest version by fetching and " +
        "verifying a release, which no poll may do"

internal class ConsoleUpgradeStatus(
    private val layout: UpgradeLayout = UpgradeLayout(EnvReader(System::getenv)),
) {

    /** The upgrade bay's payload: the three facts, each with the basis it rests on. */
    fun json(): String {
        val rollback = rollbackTarget()
        return buildJsonObject {
            // ALWAYS MEASURED: the current link's target, or this build's own version on a flat
            // install. Nothing to be unavailable about, so no basis field is needed to qualify it.
            put("installed", layout.installedVersion())

            put("latest", JsonNull)
            put("latest_basis", BASIS_UNAVAILABLE)
            put("latest_unavailable_reason", LATEST_NOT_CHECKED)

            put("rollback_target", rollback.value)
            put("rollback_basis", rollback.basis)
            if (rollback.basis == BASIS_UNAVAILABLE) {
                put("rollback_unavailable_reason", rollback.reason)
            } else {
                put("rollback_unavailable_reason", JsonNull)
            }

            // Never succeeded — NOT zero and NOT the epoch.
            put("checked_at_epoch_millis", JsonNull)
        }.toString()
    }

    /** The previous release, or the reason the layout could not be read.
     *
     *  NULL IS A MEASUREMENT HERE. `pointedVersion` answers null only when the link is not a symlink at
     *  all, so a null TARGET means there is genuinely no previous release — a measured fact, not a
     *  failure. A filesystem that REFUSES the read throws instead, and that is the case the basis
     *  exists for: reporting it as "no previous release" would be the same did-not-run-wearing-a-
     *  legitimate-answer defect this whole payload is shaped around. */
    private fun rollbackTarget(): Basis {
        val target = Cancellables.runCatchingCancellable { layout.pointedVersion(layout.previous) }
        val failure = target.exceptionOrNull()
        return when {
            // NAMED ARGUMENTS: three fields whose types do not stop a swap being written by hand, and
            // the value and the basis drifting apart is the one defect this record exists to prevent.
            failure != null -> Basis(
                value = null,
                basis = BASIS_UNAVAILABLE,
                reason = "the release layout could not be read: ${SafeFailureText.render(failure)}",
            )
            else -> Basis(value = target.getOrNull(), basis = BASIS_MEASURED, reason = null)
        }
    }

    /** One fact with the basis it rests on. A named record rather than three parallel locals, because
     *  the basis and its reason must never be able to drift apart from the value they qualify. */
    private data class Basis(val value: String?, val basis: String, val reason: String?)
}
