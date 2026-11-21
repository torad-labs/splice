// The two schemes a web address splice reads or writes may have, named once for the features that tell them apart: a
// plain-http address is taken only on this machine, and a listener says which of the two it speaks.
package splice.core.wire

/** The scheme of a plain-http address. */
public const val HTTP_SCHEME: String = "http"

/** The scheme of an https address. */
public const val HTTPS_SCHEME: String = "https"
