package splice.observability.profile

import java.nio.file.Path

/** A single owned capture. close stops it on success, cancellation and setup/copy failures. */
public interface ProfileCapture : AutoCloseable {
    public val fromMs: Long? get() = null
    public val untilMs: Long? get() = null
    public fun start()
    public fun finish(destination: Path)
}

internal class ProfileUnavailable : java.io.IOException("Profiler mode unavailable")
internal class ProfileOversized : java.io.IOException("Profile exceeds the artifact byte limit")
internal class ProfileTimedOut : java.io.IOException("Profile control deadline expired")
