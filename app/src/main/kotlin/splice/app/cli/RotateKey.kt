// Key rotation (Oct 26, 2026): `splice rotate-key`, a verb beside the ones Command.kt declares, as PendingLoginCommand
// is. Its work is RotateKeyCommand's.
package splice.app.cli

import splice.app.cli.auth.RotateKeyCommand

/** `splice rotate-key`: replaces the management key in the running daemon. */
internal data object RotateKey : Command() {
    override suspend fun run(): Int = RotateKeyCommand().run()
}
