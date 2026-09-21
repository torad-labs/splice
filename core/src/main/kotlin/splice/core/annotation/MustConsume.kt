// NEW: (discipline L4, 2026-07-19) the must-use marker enforced by the :fir-checks compiler plugin.
// Shape borrowed from Rust's #[must_use]: a value the caller MUST consume — dropping it on the floor
// is a COMPILE ERROR, not a lint. Lives in :core (framework-free) so every producer module can wear
// it without a new dependency; the wall that reads it is MustConsumeDiscardChecker in :fir-checks.
//
// Retention is BINARY (not SOURCE): the marker must survive into Kotlin metadata so a cross-module
// FIR checker resolving a callee from :core's jar can still see the annotation at :app/:gateway
// compile time. SOURCE would vanish before any downstream module compiled against :core.
//
// CLASS target lets a later (G7-gated) PR annotate a whole type — e.g. RefreshOutcome — once and
// cover every producer that returns it, exactly as #[must_use] on a struct does. FUNCTION and
// PROPERTY_GETTER target individual producers directly.
package splice.core.annotation

@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
)
public annotation class MustConsume
