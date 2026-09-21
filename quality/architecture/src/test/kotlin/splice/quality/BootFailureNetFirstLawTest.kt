// NEW: JW-01's ordering half — the boot-failure net is installed BEFORE the topology parse
// (ported from the jw_01_boot_stderr_not_devnull wall, restructure PR 6 §2.7).
//
// WHY THIS EXISTS. A pre-logger boot throwable (broken TOML, an unwritable state dir) used to die
// with nothing tailable: runDaemon parsed the topology before any sink existed. The net's own
// behaviour is pinned in app's DaemonBootFailureTest; the shim's redirect and tail are rehearsed
// in tools/release's launcher harness; DaemonLaunch's redirect is pinned in AdminSupportTest. What
// no runtime test can observe is the ORDER of two statements in Main.runDaemon — a net installed
// one line below the parse catches nothing the parse throws, and every runtime arm stays green.
// So this is a text law over Main.kt, comment-stripped: the install call precedes the parse call.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

internal object BootFailureNetFirst {
    const val MAIN_IN_APP = "splice/app/Main.kt"
    private const val INSTALL = "Thread.setDefaultUncaughtExceptionHandler(bootFailureHandler("
    private const val PARSE = "TopologyLoader.loadOrMaterialize"

    fun audit(source: String?, rel: String): List<String> {
        if (source == null) return listOf("$rel: missing — the daemon entry point is the subject of this law")
        val code = KotlinText.stripComments(source)
        val install = code.indexOf(INSTALL)
        val parse = code.indexOf(PARSE)
        return buildList {
            if (install < 0) {
                add(
                    "$rel: no boot-failure net — a pre-logger boot throwable (broken TOML, unwritable state dir) " +
                        "leaves no trace anywhere",
                )
            }
            if (parse < 0) {
                add("$rel: no $PARSE call — the parse the net exists to cover has moved; refusing to pass vacuously")
            }
            if (install >= 0 && parse in 0 until install) {
                add(
                    "$rel: the boot-failure net is installed AFTER the topology parse — exactly the throw it " +
                        "exists to catch happens before it",
                )
            }
        }
    }
}

class BootFailureNetFirstLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `Main installs the boot-failure net before the topology parse - JW-01`() {
        val main = File(map.mainSources(":app"), BootFailureNetFirst.MAIN_IN_APP)
        val problems = BootFailureNetFirst.audit(main.takeIf { it.isFile }?.readText(), KotlinText.rel(map, main))
        assertEquals(emptyList<String>(), problems) {
            problems.joinToString(separator = "\n  - ", prefix = "BOOT FAILURE NET FIRST (JW-01) violated:\n  - ")
        }
    }

    @Test
    fun `the law can actually fail - order, absence, and a net that survives only as a comment`() {
        assertEquals(emptyList<String>(), audit(INSTALL_LINE + PARSE_LINE), "net before parse is GREEN")
        assertHit(audit(PARSE_LINE + INSTALL_LINE), "installed AFTER") { "net after parse must be RED" }
        assertHit(audit(PARSE_LINE), "no boot-failure net") { "a missing net must be RED" }
        assertHit(audit(INSTALL_LINE), "refusing to pass vacuously") { "a moved parse must REFUSE" }
        assertHit(audit("// $INSTALL_LINE$PARSE_LINE"), "no boot-failure net") { "a commented-out net is no net" }
        assertHit(audit(null), "missing") { "a missing Main.kt must be RED" }
    }

    private fun audit(source: String?) = BootFailureNetFirst.audit(source, "app/src/main/kotlin/splice/app/Main.kt")

    private companion object {
        const val INSTALL_LINE = "Thread.setDefaultUncaughtExceptionHandler(bootFailureHandler(bootstrapPaths))\n"
        const val PARSE_LINE = "val loaded = TopologyLoader.loadOrMaterializeWithDigest(topologyPath)\n"
    }
}
