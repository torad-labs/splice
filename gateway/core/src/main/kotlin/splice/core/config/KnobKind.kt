// NEW: knob kinds plus the derived lookup tables. Split from Knob.kt so the
// enum catalogue is not billed for the kind + indexes (concentration, 2026-08-19).
package splice.core.config

public enum class KnobKind { STRING, NUMBER, BOOL }

// Companion dissolved (Kotlin style law, 2026-08-16 — HD-M8). Both are derived TABLES, not
// functions, so the law's sanctioned home is file scope; they are RENAMED on the way out because a
// package-scope `byKey` / `restartRequiredKeys` says nothing about what it indexes, and consumers
// import them by name. Computed once at class-init exactly as the companion's vals were.
// FILE SCOPE ON PURPOSE: one map and one list per process, never per Knob read.
public val knobsByKey: Map<String, Knob> = Knob.entries.associateBy { it.key }
public val restartRequiredKnobKeys: List<String> = Knob.entries.filter { it.restartRequired }.map { it.key }
