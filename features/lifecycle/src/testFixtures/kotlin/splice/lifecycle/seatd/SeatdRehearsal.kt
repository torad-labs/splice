package splice.lifecycle.seatd

import splice.core.util.EnvReader
import splice.lifecycle.upgrade.UpgradeFetch
import java.util.Base64

/** Offline scratch release: the archive bytes and the published scratch sha256sums.txt entry, not a production pin. */
public class SeatdRehearsal(private val env: EnvReader) {
    public fun installer(version: String = "0.1.0"): SeatdCompanion = fixture(version) { ARCHIVE }
    public fun mismatched(): SeatdCompanion = fixture("0.1.0") { "altered archive".toByteArray() }
    public fun missingAsset(): SeatdCompanion = fixture("0.1.0") { null }

    private fun fixture(version: String, fetch: UpgradeFetch): SeatdCompanion {
        val platform = SeatdPlatform()
        val installer = SeatdInstaller(env)
        val pin = requireNotNull(SeatdPins.bundled).copy(
            version = version,
            platforms = mapOf(requireNotNull(platform.key()) to SeatdAsset(ASSET, SUM)),
        )
        return object : SeatdCompanion by installer {
            override fun installed(): SeatdInstaller.Location? = installer.installed(pin, platform)

            override fun install(retryCommand: String): SeatdInstaller.Result =
                installer.install(pin, platform, retryCommand, fetch)
        }
    }
}

private const val ASSET = "seatd-pipe-0.1.0-darwin-arm64.tar.gz"
private const val SUM = "41ed43e955e9ccce2d7ab41e9975f47ab586a3ca95660deade9066310ded92c4"
private val ARCHIVE = Base64.getDecoder().decode(
    "H4sIAAAAAAAAA+3UwQqCQBDGcc89hS9Q7Lqre44SEsLCvHQSyQ4dIlF7/4yiQgovmUj/32UOOzALs9+W+7TKxvkh31udETXP09cq" +
        "jSte641UlnSVcLR2jKj7pKM8Zdmiuys9ncsqLWzbOqbF7lR+7ms7H6jysf9R31dBD5bBzA83fqcz2vMvGvmXWhjy/wv3/RP+PxUvg" +
        "mierKdRvE3CVVy/hs3kmH13Rmv+HdPIvzKuJP+/8H7/fAcAAAAAAAAAAAAAMDQXsilDDQAoAAA=",
)
