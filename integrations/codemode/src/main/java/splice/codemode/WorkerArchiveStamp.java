package splice.codemode;

/** Loaded through the JVM's class loader, not a resource URL that can reopen a replaced pathname. */
final class WorkerArchiveStamp {
    private WorkerArchiveStamp() {}

    static String fingerprint() {
        return WorkerArchiveFingerprint.VALUE;
    }

    static String[] entries() {
        return WorkerArchiveFingerprint.ENTRIES.clone();
    }
}
