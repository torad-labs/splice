// NEW: the two index spaces of the streaming machine, as distinct types (#954 opaque-seam
// move). The Node machine maps upstream output_index ints onto wire block-index ints in one
// Map<int,int> — mixing the spaces is a latent bug class there and a compile error here.
package splice.core.index

@JvmInline
public value class UpstreamItemIndex(public val value: Int)

@JvmInline
public value class WireBlockIndex(public val value: Int)
