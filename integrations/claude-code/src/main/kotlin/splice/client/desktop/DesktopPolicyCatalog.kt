// NEW: desktop-policy-console. The Claude Desktop policy keys a person may set from splice's console, in the five
// groups Settings shows them in, each with its words and the values it takes (DesktopPolicyKind). Every other key
// splice writes is its own: the connection to the door, the credential helper, the model list and the 3P deployment
// it opens on ([DesktopPolicyCatalog.locked]). A key in neither list is no key the console sets.
package splice.client.desktop

import splice.client.desktop.DesktopPolicyKind.Choice
import splice.client.desktop.DesktopPolicyKind.WholeNumber

/** One of the groups Settings shows the policy keys in, by its wire id. */
public enum class DesktopPolicyGroup(public val wire: String, public val title: String) {
    SURFACES("surfaces", "Surfaces"),
    CODE("code", "Code & models"),
    EXTENSIONS("extensions", "MCP, extensions & plugins"),
    BROWSER("browser", "Browser & network"),
    EXPERIENCE("experience", "UX & import"),
}

/** One policy key the console sets: Desktop's flat key, its group, its kind, and its words. */
public data class DesktopPolicyField(
    val key: String,
    val group: DesktopPolicyGroup,
    val kind: DesktopPolicyKind,
    val label: String,
    val description: String,
)

/** The policy keys a person sets from the console, and the ones splice holds itself. */
public class DesktopPolicyCatalog {
    /** Every key the console sets, in the order Settings shows them. */
    public val fields: List<DesktopPolicyField> = surfaces() + code() + extensions() + browser() + experience()

    private val byKey: Map<String, DesktopPolicyField> = fields.associateBy { it.key }

    /** The keys splice writes itself and the console never sets: the door, its credential, the model list, and the
     *  3P deployment Desktop opens on. */
    public val locked: List<String> = listOf(
        "inferenceProvider", "inferenceGatewayBaseUrl", "inferenceGatewayAuthScheme", "inferenceCredentialHelper",
        "inferenceCredentialHelperTtlSec", "inferenceCustomHeaders", "inferenceModels", "deploymentMode",
        "disableDeploymentModeChooser", "modelDiscoveryEnabled", "inferenceGatewayAutoModeServerChecks",
        "inferenceModelPricingEnabled", "inferenceStreamIdleTimeoutSec", "claudeCodeProcessWrapperEnabled",
        "deploymentDisplayName",
    )

    /** The field for [key], or null when the console does not set it. */
    public fun field(key: String): DesktopPolicyField? = byKey[key]

    private fun surfaces() = In(DesktopPolicyGroup.SURFACES).run {
        listOf(
            of("desktopHome", Choice(HOMES), "Home", "What Desktop opens on; no-vm runs Cowork without its VM"),
            switch("isClaudeCodeForDesktopEnabled", "Code tab", "Claude Code inside Desktop"),
            switch("projectsEnabled", "Projects", "Chat and Cowork projects"),
            switch("scheduledTasksEnabled", "Scheduled tasks", "Tasks that run on a schedule"),
            switch("skillCreationEnabled", "Skill creation", "Desktop may write new skills"),
        )
    }

    private fun code() = In(DesktopPolicyGroup.CODE).run {
        listOf(
            of("defaultModelEffort", Choice(desktopEffortLevels), "Default effort", "Effort a new session starts at"),
            switch("modelPrefer1mContext", "Prefer 1M context", "Use the long window where a model has one"),
            of("codeDefaultPermissionMode", Choice(desktopPermissionModes), "Permission mode", "How Code asks first"),
            switch("autoModeEnabled", "Auto mode", "Code sessions may run in auto mode"),
            switch("promptSuggestionEnabled", "Prompt suggestions", "Suggest the next prompt"),
        )
    }

    private fun extensions() = In(DesktopPolicyGroup.EXTENSIONS).run {
        listOf(
            switch("isDesktopExtensionEnabled", "Desktop extensions", "Install .mcpb extensions"),
            switch("isLocalDevMcpEnabled", "Local MCP servers", "Servers from Desktop's own config file"),
            switch("mcpPersistentAlwaysAllowEnabled", "Remember Always allow", "Keep a tool's Always allow"),
            of("mcpToolTimeoutSec", WholeNumber(MCP_TIMEOUT_MIN, MCP_TIMEOUT_MAX), "MCP tool timeout", TIMEOUT_ABOUT),
            of("allowedPluginMarketplaces", DesktopPolicyKind.Marketplaces, "Plugin marketplaces", MARKETS_ABOUT),
            switch("userPluginMarketplacesEnabled", "Add marketplaces", "People may add their own"),
            switch("userPluginUploadsEnabled", "Upload plugins", "People may upload a plugin"),
        )
    }

    private fun browser() = In(DesktopPolicyGroup.BROWSER).run {
        listOf(
            switch("builtinBrowserEnabled", "Built-in browser", "Claude's own browser tools"),
            of("builtinBrowserDefaultDomainPolicy", Choice(DOMAIN_POLICIES), "Sites by default", "Allow or block"),
            of("coworkEgressAllowedHosts", DesktopPolicyKind.Hosts, "Cowork hosts", "Hosts Cowork may reach; * is all"),
            switch("skipWebFetchPreflight", "Skip fetch check", "Fetch pages without a preflight request"),
        )
    }

    private fun experience() = In(DesktopPolicyGroup.EXPERIENCE).run {
        listOf(
            switch("toolSearchEnabled", "Tool search", "Load tools as a session needs them"),
            switch("keepAwakeEnabled", "Keep awake", "Keep the computer awake during a task"),
            switch("developerModeEnabled", "Developer mode", "Desktop's developer tools"),
            switch("quickEntryScreenshotsEnabled", "Quick entry screenshots", "Attach a screenshot"),
            switch("quickEntryFileSharingEnabled", "Quick entry files", "Attach a file"),
            switch("userFileUploadsEnabled", "File uploads", "Upload files into a chat"),
            of("claudeAiImport", DesktopPolicyKind.Import, "claude.ai import", "Offer to import earlier history"),
        )
    }

    /** The fields of one [group]. */
    private class In(private val group: DesktopPolicyGroup) {
        fun of(key: String, kind: DesktopPolicyKind, label: String, about: String) =
            DesktopPolicyField(key, group, kind, label, about)

        fun switch(key: String, label: String, about: String) = of(key, DesktopPolicyKind.Switch, label, about)
    }
}

// The values Desktop's schema gives each choice that Claude Code has no list of.
private val HOMES = listOf("standard", "simple", "no-vm", "off")
private val DOMAIN_POLICIES = listOf("allow", "block")

// why: the shortest MCP tool call timeout Desktop's schema takes, in seconds.
private const val MCP_TIMEOUT_MIN = 60

// why: the longest MCP tool call timeout Desktop's schema takes, in seconds (an hour).
private const val MCP_TIMEOUT_MAX = 3600

// What the marketplace list holds, as its editor reads it.
private const val MARKETS_ABOUT = "GitHub repositories (owner/repo) or marketplace URLs"

// What the MCP timeout bounds, as its row reads it; the field says its unit and range.
private const val TIMEOUT_ABOUT = "How long one MCP tool call may run"
