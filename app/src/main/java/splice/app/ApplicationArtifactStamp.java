package splice.app;

/** A method bridge prevents callers from inlining the generated build constant. */
public final class ApplicationArtifactStamp {
    private ApplicationArtifactStamp() {}

    public static String fingerprint() {
        return ApplicationArtifactFingerprint.VALUE;
    }
}
