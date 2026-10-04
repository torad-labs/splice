// NEW: the jar this process runs from, as a port (LAYOUT-01). Only the executable can answer it — app
// locates its own class file — and three capabilities ask: doctor's install probes, status's jar
// line and the daemon cold start that relaunches the same build. Declared once here so each asks
// the one contract instead of a copy per feature.
package splice.core.config

import java.nio.file.Path

/** The jar this process runs from, or null when it runs from classes (a dev build). */
public fun interface RunningJar {
    public fun path(): Path?
}
