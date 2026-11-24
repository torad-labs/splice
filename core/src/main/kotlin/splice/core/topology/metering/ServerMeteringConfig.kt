package splice.core.topology.metering

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public const val DEFAULT_SERVER_METRICS_PREFIX: String = "splice_server_"

/** Trusted resource key names for the authenticated person's identity and team in relayed OTLP. */
@Serializable
public data class OtlpIdentityAttributes(
    val person: String = "splice.person",
    val email: String = "user.email",
    val team: String = "splice.team",
)

/** One opt-in hourly meter. Exactly one source, [file], [url] or [argv], must be set. */
@Serializable
public data class ServerCostMeter(
    @SerialName("hourly_usd") val hourlyUsd: Double? = null,
    @SerialName("poll_seconds") val pollSeconds: Int = 60,
    val file: String? = null,
    val url: String? = null,
    val argv: List<String>? = null,
)
