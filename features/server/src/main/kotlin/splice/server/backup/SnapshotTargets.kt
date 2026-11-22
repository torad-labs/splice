// NEW: server mode (docs/specs/server-mode.md, F14). Resolves `[server.backup] to` into a folder or S3 snapshot target.
package splice.server.backup

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.topology.FILE_SCHEME
import splice.core.topology.S3_SCHEME
import splice.core.topology.ServerBackup
import splice.server.export.BucketPlace
import splice.server.export.BucketReach
import splice.server.export.SigV4
import java.net.URI
import java.nio.file.Path

internal class SnapshotTargets(private val io: CoroutineDispatcher) {
    fun of(backup: ServerBackup, reach: BucketReach): SnapshotTarget? {
        val to = backup.to.trim()
        return when {
            to.startsWith(FILE_SCHEME) -> FolderSnapshotTarget(Path.of(to.removePrefix(FILE_SCHEME)), io)
            to.startsWith(S3_SCHEME) -> BucketSnapshotTarget(place(backup, to.removePrefix(S3_SCHEME)), reach, io)
            else -> null
        }
    }

    private fun place(backup: ServerBackup, named: String): BucketPlace {
        val region = backup.region?.takeIf(String::isNotBlank) ?: BACKUP_DEFAULT_REGION
        val given = backup.endpoint?.takeIf(String::isNotBlank)?.trimEnd('/')
        val endpoint = URI(given ?: "https://s3.$region.amazonaws.com")
        val bucket = SigV4.Paths.of(named.substringBefore('/'))
        val prefix = named.substringAfter('/', "").trim('/').let { if (it.isEmpty()) "" else SigV4.Paths.of(it) + "/" }
        val base = if (backup.pathStyle) {
            "$endpoint/$bucket/$prefix"
        } else {
            val port = if (endpoint.port == -1) "" else ":${endpoint.port}"
            "${endpoint.scheme}://$bucket.${endpoint.host}$port${endpoint.rawPath.orEmpty()}/$prefix"
        }
        return BucketPlace(URI(base), region)
    }
}

private const val BACKUP_DEFAULT_REGION = "us-east-1"
