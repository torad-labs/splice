package splice.release

import java.io.File
import java.util.Properties

/** Native release artifacts are dependencies too; their identities and license belong in the BOM. */
object NativeCodeModeInventory {
    fun components(
        manifests: Collection<File>,
    ): List<LinkedHashMap<Any?, Any?>> = manifests.sortedBy(File::getPath).map {
        val metadata = Properties().apply { it.inputStream().use(::load) }
        val target = metadata.getProperty("target")
        val version = metadata.getProperty("release")
        val reference = "pkg:generic/openai/codex-code-mode-host@$version?arch=$target"
        linkedMapOf<Any?, Any?>(
            "type" to "application",
            "group" to "openai",
            "name" to "codex-code-mode-host-$target",
            "version" to version,
            "bom-ref" to reference,
            "purl" to reference,
            "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to metadata.getProperty("sha256"))),
            "licenses" to listOf(mapOf("license" to mapOf("id" to "Apache-2.0"))),
            "externalReferences" to listOf(mapOf("type" to "distribution", "url" to metadata.getProperty("origin"))),
        )
    }
}
