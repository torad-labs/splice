// NEW: server mode (docs/specs/server-mode.md, F5, ledger-export). What a bucket export is built from (BucketTarget):
// where the bucket is, and how it is reached. The key pair is named here by its environment variables only, and read
// at each export.
package splice.server.export

import splice.core.util.EnvReader
import splice.upstream.Waiter
import java.net.URI
import java.time.Clock

/** Where a bucket is: [base] is the URL a key is put under, `.../bucket/prefix/` path-style. */
internal data class BucketPlace(val base: URI, val region: String)

/** The environment variables the bucket's key pair is read from, by name. */
internal data class BucketKeyNames(val access: String, val secret: String)

/** What a bucket export needs besides its place: the key pair's variables, the environment, and its clock. */
internal data class BucketReach(val names: BucketKeyNames, val env: EnvReader, val clock: Clock, val waiter: Waiter)
