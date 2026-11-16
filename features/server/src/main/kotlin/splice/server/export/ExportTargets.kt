// NEW: server mode (docs/specs/server-mode.md, F5, ledger-export). The target `[server.export] to` names, built as the
// daemon opens: `file:///dir` is a folder, `s3://bucket/prefix` a bucket at `endpoint` (AWS's own for `region` when
// none is set), path-style or virtual-hosted as `path_style` says. Boot has already refused a `to` that is neither.
package splice.server.export

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.topology.FILE_SCHEME
import splice.core.topology.S3_SCHEME
import splice.core.topology.ServerExport
import java.net.URI
import java.nio.file.Path

internal class ExportTargets(private val io: CoroutineDispatcher) {
    /** The target [export] names, its bucket signed with the key [reach] reads; null when [export] names none. */
    fun of(export: ServerExport, reach: BucketReach): ExportTarget? {
        val to = export.to.trim()
        return when {
            to.startsWith(FILE_SCHEME) -> FolderTarget(Path.of(to.removePrefix(FILE_SCHEME)), io)
            to.startsWith(S3_SCHEME) -> BucketTarget(place(export, to.removePrefix(S3_SCHEME)), reach, io)
            else -> null
        }
    }

    /** Where `bucket/prefix` is, as [export] says it is reached. */
    private fun place(export: ServerExport, named: String): BucketPlace {
        val region = export.region?.takeIf(String::isNotBlank) ?: DEFAULT_REGION
        val given = export.endpoint?.takeIf(String::isNotBlank)?.trimEnd('/')
        val endpoint = URI(given ?: "https://s3.$region.amazonaws.com")
        val bucket = SigV4.Paths.of(named.substringBefore('/'))
        val prefix = named.substringAfter('/', "").trim('/').let { if (it.isEmpty()) "" else SigV4.Paths.of(it) + "/" }
        val base = if (export.pathStyle) {
            "$endpoint/$bucket/$prefix"
        } else {
            val port = if (endpoint.port == -1) "" else ":${endpoint.port}"
            "${endpoint.scheme}://$bucket.${endpoint.host}$port${endpoint.rawPath.orEmpty()}/$prefix"
        }
        return BucketPlace(URI(base), region)
    }
}

// The region a bucket is signed for when `[server.export]` names none: the one S3-compatible stores take by default.
private const val DEFAULT_REGION = "us-east-1"
