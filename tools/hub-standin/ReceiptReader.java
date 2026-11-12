import java.nio.file.Path;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonObject;
import splice.core.config.StatePaths;
import splice.core.mcp.McpToolResultStore;

/** JDK source-launcher fixture: reads the real native carrier through the supplied candidate jar's core API. */
public final class ReceiptReader {
    public static void main(String[] args) {
        if (args.length != 3) throw new IllegalArgumentException("private state, callback and synthetic arguments required");
        Path root = Path.of(args[0]);
        StatePaths paths = new StatePaths(root, name -> null, root);
        McpToolResultStore store = new McpToolResultStore(paths, System::currentTimeMillis, new McpToolResultStore.Limits());
        JsonObject arguments = (JsonObject) Json.Default.parseToJsonElement(args[2]);
        McpToolResultStore.Read read = store.read(args[1], arguments);
        if (read instanceof McpToolResultStore.Read.Found found) {
            System.out.println(Json.Default.encodeToString(McpToolResultStore.Receipt.Companion.serializer(), found.getReceipt()));
        } else if (read instanceof McpToolResultStore.Read.Missing) {
            System.out.println("{\"status\":\"missing\"}");
        } else {
            System.out.println("{\"status\":\"refused\"}");
        }
    }
}
