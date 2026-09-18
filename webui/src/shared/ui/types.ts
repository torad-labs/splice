// The two closed vocabularies the strip world speaks. Both are printed as text
// on the primitive that uses them: an edge state never rides on color alone, and
// a basis is always a word beside the figure it qualifies. Keeping them in one
// leaf file (not a component file) is what stops Strip, Figure and ScopeInset
// from importing each other for a type.
export type Edge = 'green' | 'amber' | 'red' | 'grey';
export type Basis = 'measured' | 'estimated' | 'unavailable' | 'stale';
