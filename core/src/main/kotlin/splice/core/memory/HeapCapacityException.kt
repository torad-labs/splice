// NEW: admission capacity never authorizes deleting durable evidence or rerunning executed source.
package splice.core.memory

import java.io.IOException

/** Capacity is not corrupt input, disk failure or permission to discard durable evidence. */
public class HeapCapacityException : IOException("daemon heap budget is full; retry without rerunning source")
