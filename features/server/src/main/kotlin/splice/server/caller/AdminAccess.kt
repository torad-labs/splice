package splice.server.caller

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import splice.core.listen.IpLiterals
import splice.core.topology.ServerConfig

internal class AdminAccess(
    private val server: ServerConfig,
    private val addresses: ClientAddresses,
) {
    private val ranges = server.adminCidrs.map { requireNotNull(IpLiterals.prefix(it)) }

    fun names(person: String): Boolean = server.admin(person)

    fun allows(call: ApplicationCall, person: String): Boolean = names(person) && allowsAddress(call)

    fun allowsAddress(call: ApplicationCall): Boolean {
        if (ranges.isEmpty()) return true
        val forwarded = call.request.headers.getAll(HttpHeaders.XForwardedFor)?.joinToString(",")
        val client = addresses.verified(call.request.origin.remoteAddress, forwarded)?.let(IpLiterals::parse)
            ?: return false
        return ranges.any { it.holds(client) }
    }
}
