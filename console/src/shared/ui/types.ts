// The two closed vocabularies the strip world speaks. Both are printed as text
// on the primitive that uses them: an edge state never rides on color alone, and
// a basis is always a word beside the figure it qualifies. Keeping them in one
// leaf file (not a component file) is what stops Strip, Figure and ScopeInset
// from importing each other for a type.
export type Edge = 'green' | 'amber' | 'red' | 'grey';
// NOT AN ABSENCE PHRASE (M1-69): `unavailable` here is a TYPE MEMBER of the basis vocabulary - a
// word the console prints beside a figure to say what kind of figure it is - and the census counts
// the declaration because it is quoted. It is the same word the display vocabulary would use for
// "it exists and we cannot reach it", which is why it is easy to count twice.
export type Basis = 'measured' | 'estimated' | 'unavailable' | 'stale';
