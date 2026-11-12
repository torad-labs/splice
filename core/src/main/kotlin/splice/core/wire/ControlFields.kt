// NEW: the JSON field names the control API and the usage and heads payloads share, declared once in :core, the one
// module that features and the composition root both import. One declaration makes them one term in one vocabulary:
// /health and /api/heads both say "heads", and the head rows say "key" and "label". A body puts a different value
// under a name (a count in /health, an array in /api/heads); the name is the same term in each.
package splice.core.wire

public object ControlFields {
    public const val HEADS: String = "heads"
    public const val KEY: String = "key"
    public const val LABEL: String = "label"
}
