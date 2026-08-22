// NEW: the install path wrappers — localBin / shareDir / launchShimPath. Kept as
// named methods (not inlined into install()) so install.sh and `splice install`
// always agree on where wrappers and the shim land. Split from InstallCommand.kt
// (concentration HIGH, 2026-08-19).
package splice.app.cli

import splice.core.config.InstallPaths
import splice.core.util.EnvReader
import java.nio.file.Path

internal class InstallLayout {
    // SPLICE_BIN_DIR / SPLICE_SHARE_DIR honored via core/config (System.getenv is walled there).
    fun localBin(env: EnvReader): Path = InstallPaths(envReader = env).binDir

    fun shareDir(env: EnvReader): Path = InstallPaths(envReader = env).shareDir

    fun launchShimPath(env: EnvReader): Path = shareDir(env).resolve("splice-launch")
}
