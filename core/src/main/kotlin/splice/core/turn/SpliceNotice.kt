// NEW: V4-385 — one exact signature distinguishes splice's own wait notice from model thinking.
package splice.core.turn

/** The progress pinger's thinking block is client-visible but not model-authored reasoning. The
 *  writer stamps this signature and every upstream request path that can replay thinking drops it. */
public object SpliceNotice {
    public const val SIGNATURE: String = "splice-wait-notice-v1"
}
