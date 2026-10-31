// The native oracle, production candidate and frozen baseline execute these
// exact source bytes. A merely similar repro is not failing-baseline evidence.
export const explicitYieldSource = "text('reference-before'); yield_control(); await new Promise(r => setTimeout(r, 80)); text('reference-after');";
