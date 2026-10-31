// The daemon-lifecycle ports of [ConsolePorts]: the upgrade status, its check and its run, whether systemd owns this
// daemon, and the replacement of its management key. Moved out of ConsolePorts.kt (concentration, Oct 12): they are one
// subject, the daemon's own version, restart and key, and they were the only reason ConsolePorts named
// splice.lifecycle.
//
// Key rotation (Oct 26, 2026): [KeyRotation], the daemon's half of `splice rotate-key`, sits beside the port that holds
// it (concentration, Oct 26: a file of its own lightened splice.app.control's median under its neighbours). A
// management key that was shown somewhere it should not have been is replaced while the daemon runs, and no running
// session is stranded by it:
//
//   1. The key is replaced in memory and on disk (MgmtKey.replace). Every holder reads it per request: the control
//      guard, the console key, and each head's tokens (HeadDeps.HeadTokens). The old key, and the turn key made from
//      it, open nothing from this moment.
//   2. The turn-key header file is rewritten. A session splice launched reads its turn key from it through Claude
//      Code's apiKeyHelper, which Claude Code runs again after a 401 before it sends the same request again, so the
//      session's next turn goes through on its own head. Its status line and hooks read the same file.
//   3. The MCP access key bound to the old key is revoked and a new one published (McpAccessKey). A session that holds
//      the old one reaches its hosted MCP servers again once it is relaunched.
//   4. The pages an older `splice console` and the removed `splice dashboard` left in the state folder are deleted:
//      each sends a browser to an address that carries the old key.
//
// The answer is paths and words. No key is in it, and none is logged.
package splice.app.control

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.client.mcp.McpAccessKey
import splice.core.config.CONSOLE_LAUNCH_PAGE
import splice.core.config.DASHBOARD_PAGE
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.lifecycle.restart.DaemonSupervised
import splice.lifecycle.upgrade.UpgradeChecks
import splice.lifecycle.upgrade.UpgradeRuns
import splice.lifecycle.upgrade.UpgradeStatus
import java.io.IOException
import java.nio.file.Files

// The words of the rotation's answer that `splice rotate-key` reads (RotateKeyCommand).
internal const val HEADER_WRITTEN = "written"
internal const val MCP_REPLACED = "replaced"
internal const val RETIRED_KEPT = "kept"

/** The upgrade and restart ports, kept to [ConsolePorts]' discipline: each is assigned by ConsoleWiring after the
 *  server is constructed, read at CALL time through a lambda, and NULL MEANS UNWIRED, answered with a named 5xx. */
internal class LifecyclePorts {
    /** V4-127: the upgrade status GET /api/upgrade reports. Null answers a named 503, never "nothing to upgrade". */
    var upgrade: UpgradeStatus? = null

    /** The Version card's "Check": the snapshot GET /api/upgrade/check reports and the read POST makes. Null answers a named 503. */
    var upgradeChecks: UpgradeChecks? = null

    /** V4-220 item 4: `splice upgrade` as the console starts it, and the run it reports. Null answers
     *  a named 503 on POST /api/upgrade and GET /api/upgrade/run, never a run that never started. */
    var upgradeRuns: UpgradeRuns? = null

    /** V4-137: whether systemd owns this daemon. A supervised daemon drains into its unit; an unsupervised one
     *  arms a detached successor before draining.
     *
     *  NULL IS NOT "ASSUME SUPERVISED". The route refuses an unwired probe: choosing the unit for a
     *  daemon started by hand would drain it without a successor. */
    var supervised: DaemonSupervised? = null

    /** Key rotation (Oct 26, 2026): `splice rotate-key`'s replacement of the management key. Null answers a named
     *  503, and the key stays as it was. */
    var keyRotation: KeyRotation? = null
}

/** Replaces the management key and everything made from it, in one call (`POST /api/mgmt-key/rotate`). */
internal class KeyRotation(
    private val mgmtKey: MgmtKey,
    private val mcpAccess: McpAccessKey,
    private val statePaths: StatePaths,
    private val log: LogSink,
) {
    private val turnKey = TurnKey(mgmtKey)

    /** One rotation at a time, so each one keeps the key it replaces: two at once could both keep the first key, and
     *  the key minted between them would then be neither current nor known as splice's. */
    private val rotating = Any()

    /** The key is replaced first: a failure after it is reported in the answer, never undone, because the old key is
     *  the one that was exposed. Only the old key's record as a retired key comes before it, so no moment passes in
     *  which that key is neither current nor known as splice's. */
    fun rotate(): JsonObject = synchronized(rotating) {
        val retired = retireCurrent()
        mgmtKey.replace()
        val header = rewriteHeader()
        val mcp = replaceMcpAccess()
        val removed = listOf(CONSOLE_LAUNCH_PAGE, DASHBOARD_PAGE).filter(::deleted)
        log(
            "[mgmt-key] the management key was replaced; the old key and its turn key are refused from now on " +
                "(kept as retired: ${LogSafe.str(retired)}, turn-key file: ${LogSafe.str(header)}, " +
                "MCP access key: ${LogSafe.str(mcp)}, pages removed: ${LogSafe.list(removed)})\n",
        )
        buildJsonObject {
            put("replaced", true)
            put("key_file", statePaths.mgmtKeyFile.toString())
            put("retired_keys", retired)
            put("turn_header", header)
            put("mcp_access", mcp)
            putJsonArray("pages_removed") { removed.forEach { add(it) } }
        }
    }

    /** Keeps the key about to be replaced, and the turn key made from it, known as splice's ([MgmtKey.retired]), so the
     *  launch shim, a forwarding head and `splice sessions relaunch` still know them once they open nothing. A failure
     *  is reported and the rotation goes on: the old key is replaced whether or not it is kept. */
    private fun retireCurrent(): String = try {
        mgmtKey.retired.retire(mgmtKey.get())
        RETIRED_KEPT
    } catch (e: IOException) {
        SafeFailureText.render(e)
    }

    private fun rewriteHeader(): String = try {
        turnKey.headerFile()
        HEADER_WRITTEN
    } catch (e: IOException) {
        SafeFailureText.render(e)
    }

    /** Deletes [page] from the state folder when it is there. One that cannot be deleted stays, and is not named. */
    private fun deleted(page: String): Boolean = try {
        Files.deleteIfExists(statePaths.stateDir.resolve(page))
    } catch (_: IOException) {
        false
    }

    private fun replaceMcpAccess(): String = try {
        mcpAccess.managementReplaced()
        MCP_REPLACED
    } catch (e: IOException) {
        SafeFailureText.render(e)
    } catch (_: UnsupportedOperationException) {
        "MCP state requires owner-only filesystem permissions"
    }
}
