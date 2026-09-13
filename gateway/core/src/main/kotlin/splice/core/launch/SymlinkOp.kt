// The symlink primitive ClaudeConfigMaterializer links through — a seam so the DR-67 safety
// property is testable. Split from ClaudeConfigMaterializer.kt (concentration, 2026-09-13).
package splice.core.launch

import java.nio.file.Path

/** Creates one symbolic link. A seam because the swap's whole safety property — that a failure
 *  NEVER destroys the operator's pre-existing file — is only testable on the production path if the
 *  create can be made to fail on demand (no temp filesystem denies createSymbolicLink), and that
 *  failure is exactly the ENOSPC/LSM-EPERM case DR-11 was opened for. Public because it is a
 *  default param of a public constructor and the no-secondary-constructor law leaves one init
 *  path: an internal type here would trip "public constructor exposes internal parameter type". */
public fun interface SymlinkOp {
    public operator fun invoke(link: Path, target: Path)
}
