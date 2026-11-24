package splice.core.topology.metering

import splice.core.topology.ServerConfig
import splice.core.topology.TopologyFinding
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class ServerMeteringChecks(private val server: ServerConfig) {
    fun findings(): List<TopologyFinding> = listOfNotNull(prefix(), identity()) +
        server.costMeters.flatMap { (name, meter) -> meter(name, meter) }

    private fun prefix(): TopologyFinding? =
        TopologyFinding("server.metrics_prefix", "must be a Prometheus name prefix (letters, digits, _, or :)")
            .takeUnless { METRIC_PREFIX.matches(server.metricsPrefix) }

    private fun identity(): TopologyFinding? {
        val names = server.telemetry?.identityAttributes ?: return null
        val keys = listOf(names.person, names.email, names.team)
        return TopologyFinding("server.telemetry.identity_attributes", "must name three different nonblank keys")
            .takeUnless {
                keys.distinct() == keys && keys.all { key -> key.isNotBlank() && key.none(Char::isISOControl) }
            }
    }

    private fun meter(name: String, meter: ServerCostMeter): List<TopologyFinding> {
        val key = "server.cost_meters.$name"
        return listOfNotNull(
            TopologyFinding("server.cost_meters", "meter names must use letters, digits, underscores or hyphens")
                .takeUnless { METER_NAME.matches(name) },
            TopologyFinding("$key.hourly_usd", "must be a finite, nonnegative USD rate")
                .takeUnless { meter.hourlyUsd?.let { it.isFinite() && it >= 0.0 } == true },
            TopologyFinding("$key.poll_seconds", "must be positive").takeUnless { meter.pollSeconds > 0 },
            source(key, meter),
        )
    }

    private fun source(key: String, meter: ServerCostMeter): TopologyFinding? = when {
        listOfNotNull(meter.file, meter.url, meter.argv).size != 1 ->
            TopologyFinding(key, "set exactly one signal source: file, url or argv")
        meter.file?.let { !validFile(it) } == true ->
            TopologyFinding("$key.file", "must name a file")
        meter.url?.let { !validUrl(it) } == true ->
            TopologyFinding("$key.url", "must be an http(s) URL with no credentials or fragment")
        meter.argv?.let { !validArgv(it) } == true ->
            TopologyFinding("$key.argv", "must be a nonempty argv array starting with an absolute executable path")
        else -> null
    }

    private fun validArgv(argv: List<String>): Boolean {
        val executable = argv.firstOrNull() ?: return false
        return validFile(executable) && Path.of(executable).isAbsolute && argv.none { '\u0000' in it }
    }

    private fun validFile(text: String): Boolean = try {
        Path.of(text).toString().isNotBlank()
    } catch (_: InvalidPathException) {
        false
    }

    private fun validUrl(text: String): Boolean = try {
        val uri = URI(text)
        uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.fragment == null &&
            uri.port in -1..MAX_PORT
    } catch (_: URISyntaxException) {
        false
    }
}

private const val MAX_PORT = 65_535
private val METRIC_PREFIX = Regex("[A-Za-z_:][A-Za-z0-9_:]*")
private val METER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
