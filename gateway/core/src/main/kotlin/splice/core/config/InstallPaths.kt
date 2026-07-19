// NEW: install-target paths (OSS-D R5). install.sh honors SPLICE_BIN_DIR / SPLICE_SHARE_DIR;
// the jar side (`splice install`) must honor the SAME overrides or the two installers disagree
// about where wrappers and the launch shim land (the shim ends up where the jar never looks).
// Lives in core/config because System.getenv is walled to this package (kt-no-system-getenv);
// the reader stays injectable for hermetic tests (StatePaths idiom — JVM cannot setenv).
package splice.core.config

import java.nio.file.Path
import java.nio.file.Paths

public class InstallPaths(
    binOverride: Path? = null,
    shareOverride: Path? = null,
    envReader: (String) -> String? = System::getenv,
) {
    public val binDir: Path = binOverride
        ?: envReader("SPLICE_BIN_DIR")?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".local", "bin")

    public val shareDir: Path = shareOverride
        ?: envReader("SPLICE_SHARE_DIR")?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".local", "share", "splice")
}
