// NEW: install-target paths (OSS-D R5). install.sh honors SPLICE_BIN_DIR / SPLICE_SHARE_DIR;
// the jar side (`splice install`) must honor the SAME overrides or the two installers disagree
// about where wrappers and the launch shim land (the shim ends up where the jar never looks).
// Lives in core/config because System.getenv is walled to this package (kt-no-system-getenv);
// the reader stays injectable for hermetic tests (StatePaths idiom — JVM cannot setenv).
package splice.core.config

import splice.core.util.EnvReader
import java.nio.file.Path
import java.nio.file.Paths

public class InstallPaths(
    binOverride: Path? = null,
    shareOverride: Path? = null,
    envReader: EnvReader = EnvReader(System::getenv),
) {
    public val binDir: Path = binOverride
        ?: envReader("SPLICE_BIN_DIR")?.let { Paths.get(it) }
        ?: UserHome.dir(envReader).resolve(".local/bin")

    /** Only the path and port selectors a launcher needs to recognize this install's daemon. */
    public val launcherProfile: Map<String, String> = buildMap {
        for (name in listOf("SPLICE_CONFIG", "XDG_CONFIG_HOME", "SPLICE_CONTROL_PORT", "CONTROL_PROXY_PORT")) {
            envReader(name)?.takeIf(String::isNotEmpty)?.let { put(name, it) }
        }
    }

    public val shareDir: Path = shareOverride
        ?: envReader("SPLICE_SHARE_DIR")?.let { Paths.get(it) }
        ?: UserHome.dir(envReader).resolve(".local/share/splice")
}
