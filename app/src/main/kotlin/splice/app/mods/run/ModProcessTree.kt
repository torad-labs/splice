package splice.app.mods.run

import kotlinx.coroutines.withTimeoutOrNull
import splice.upstream.codemode.ProcessWaiter

private const val MOD_GROUP_STOP_MS = 4000L // why: a guarded run gets a bounded window to clear, never a second start
private const val MOD_GROUP_PROBE_MS = 50L // why: let the kernel reap the remaining members without spinning

/** New runs clear their own group; for older records only the confirmed process can be signalled. */
internal class ModProcessTree(
    private val owner: ModRunOwner,
    private val ownership: ModGroupOwnership,
) {
    private val waiter = ProcessWaiter()

    suspend fun terminate(): Boolean {
        // Do not force-kill the guardian: it is the last process allowed to use the group's ID.
        ModProcessIdentity.open(owner.leader)?.destroy()
        return withTimeoutOrNull(MOD_GROUP_STOP_MS) { waitUntilGone() } ?: false
    }

    private suspend fun waitUntilGone(): Boolean {
        while (true) {
            when (ownership.claim(owner)) {
                ModGroupClaim.Gone, ModGroupClaim.Reused -> return true
                ModGroupClaim.Unreadable -> return false
                ModGroupClaim.Held, ModGroupClaim.Draining -> waiter.wait(MOD_GROUP_PROBE_MS)
            }
        }
    }
}
