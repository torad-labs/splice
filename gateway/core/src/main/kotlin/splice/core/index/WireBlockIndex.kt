// NEW: the wire block-index space of the streaming machine, as a distinct type (#954 opaque-seam
// move). The Node machine mapped upstream output_index ints onto wire block-index ints in one
// Map<int,int> — mixing the spaces is a latent bug class there and a compile error here. The
// upstream side stays a plain Int inside each dialect's own turn ledger (it never crosses a seam),
// so only the wire side needs a type.
package splice.core.index

@JvmInline
public value class WireBlockIndex(public val value: Int)
