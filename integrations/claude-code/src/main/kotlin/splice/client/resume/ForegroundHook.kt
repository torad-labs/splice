// RETIRED 2026-10-19: a session inside a tool is known from the daemon's own wire now (ForegroundTools.opened and
// answered, fed by the turn that ends on tool calls and the request that answers them), so no head installs a client
// hook for it. The script's name stays so that HookSettings keeps it in the owned set and strips the entries an earlier
// install wrote into a head's settings; the file those entries ran is left in place for sessions that started before.
package splice.client.resume

internal object ForegroundHook {
    const val SCRIPT = "splice-foreground-hook.sh"
}
