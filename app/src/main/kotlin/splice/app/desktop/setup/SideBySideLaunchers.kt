// NEW: side-by-side-launchers. Installs and removes the optional OS application launcher for
// Claude (Splice):
//   - Linux: `${XDG_DATA_HOME:-$HOME/.local/share}/applications/ai.torad.claude-splice.desktop`
//     (the Freedesktop XDG Desktop Entry standard supported across distros, no root needed)
//   - macOS: `~/Applications/Claude (Splice).app` (user Applications folder, no sudo needed)
//
// Each launcher carries a splice-managed marker so `splice uninstall` removes only launchers
// Splice wrote and never overwrites or deletes a user-authored launcher.
package splice.app.desktop.setup

import splice.core.config.InstallPaths
import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** Result of installing the optional OS Desktop application launcher. */
internal sealed class AppLauncherInstall {
    data class Installed(val path: Path) : AppLauncherInstall()
    data class Skipped(val reason: String) : AppLauncherInstall()
}

internal class SideBySideLaunchers(
    private val env: EnvReader = EnvReader(System::getenv),
    private val osName: String = System.getProperty("os.name").orEmpty(),
) {
    private val mac: Boolean = osName.startsWith("Mac", ignoreCase = true)

    /** Where the OS application launcher lives on this platform. */
    fun desktopLauncherPath(): Path = if (mac) macAppPath() else linuxDesktopEntryPath()

    /** Human-readable description of where the optional app launcher installs on this OS. */
    fun platformHint(): String = if (mac) {
        "~/Applications/$SIDE_BY_SIDE_APP_NAME.app"
    } else {
        "~/.local/share/applications/$SIDE_BY_SIDE_DESKTOP_FILE"
    }

    /** Installs the OS application launcher pointing to `splice launch claude-desktop`. */
    fun installDesktopApp(): AppLauncherInstall = if (mac) installMacApp() else installLinuxDesktop()

    /** Removes the OS application launcher if (and only if) Splice created it. Returns true when removed. */
    fun removeIfManaged(): Boolean = if (mac) removeMacApp() else removeLinuxDesktop()

    private fun linuxDesktopEntryPath(): Path {
        val dataHome = env(XDG_DATA_HOME)?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: UserHome.dir(env).resolve(".local").resolve("share")
        return dataHome.resolve("applications").resolve(SIDE_BY_SIDE_DESKTOP_FILE)
    }

    private fun macAppPath(): Path = UserHome.dir(env).resolve("Applications").resolve("$SIDE_BY_SIDE_APP_NAME.app")

    private fun spliceBin(): Path = InstallPaths(envReader = env).binDir.resolve("splice")

    private fun installLinuxDesktop(): AppLauncherInstall {
        val target = linuxDesktopEntryPath()
        if (Files.exists(target) && !isManagedDesktopFile(target)) {
            return AppLauncherInstall.Skipped("left existing user launcher untouched at $target")
        }
        Files.createDirectories(target.parent)
        Files.writeString(target, LauncherTemplates.linuxDesktopEntry(spliceBin()))
        return AppLauncherInstall.Installed(target)
    }

    private fun installMacApp(): AppLauncherInstall {
        val appDir = macAppPath()
        val marker = appDir.resolve("Contents").resolve(MAC_MANAGED_MARKER)
        if (Files.exists(appDir) && !Files.exists(marker)) {
            return AppLauncherInstall.Skipped("left existing user app untouched at $appDir")
        }
        val contents = appDir.resolve("Contents")
        val macOsDir = contents.resolve("MacOS")
        val resourcesDir = contents.resolve("Resources")
        Files.createDirectories(macOsDir)
        Files.createDirectories(resourcesDir)
        val hasIcon = copyMacIconIfPresent(resourcesDir)
        Files.writeString(contents.resolve("Info.plist"), LauncherTemplates.macInfoPlist(hasIcon))
        val launcher = macOsDir.resolve(SIDE_BY_SIDE_DIR_NAME)
        Files.writeString(launcher, LauncherTemplates.macExecScript(spliceBin()))
        Cancellables.runCatchingBestEffort { Files.setPosixFilePermissions(launcher, EXEC_PERMS) }
        Files.writeString(marker, "managed by splice\n")
        return AppLauncherInstall.Installed(appDir)
    }

    private fun copyMacIconIfPresent(resourcesDir: Path): Boolean {
        val source = listOf(
            Path.of("/Applications/Claude.app/Contents/Resources/electron.icns"),
            UserHome.dir(env).resolve("Applications/Claude.app/Contents/Resources/electron.icns"),
        ).firstOrNull { Files.isRegularFile(it) } ?: return false
        return Cancellables.runCatchingBestEffort {
            Files.copy(source, resourcesDir.resolve("electron.icns"), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }

    private fun removeLinuxDesktop(): Boolean {
        val target = linuxDesktopEntryPath()
        if (!Files.isRegularFile(target) || !isManagedDesktopFile(target)) return false
        return Files.deleteIfExists(target)
    }

    private fun removeMacApp(): Boolean {
        val appDir = macAppPath()
        val marker = appDir.resolve("Contents").resolve(MAC_MANAGED_MARKER)
        if (!Files.isDirectory(appDir) || !Files.exists(marker)) return false
        Files.walk(appDir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        return true
    }

    private fun isManagedDesktopFile(file: Path): Boolean =
        Cancellables.runCatchingBestEffort { Files.readString(file).contains(MANAGED_COMMENT) }.getOrDefault(false)
}

private object LauncherTemplates {
    fun linuxDesktopEntry(spliceBin: Path): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine(MANAGED_COMMENT)
        appendLine("Name=$SIDE_BY_SIDE_APP_NAME")
        appendLine("Comment=Claude Desktop on Splice's gateway, every model")
        appendLine("Exec=$spliceBin launch claude-desktop %U")
        appendLine("Icon=claude-desktop")
        appendLine("Type=Application")
        appendLine("StartupNotify=true")
        appendLine("StartupWMClass=$SIDE_BY_SIDE_WM_CLASS")
        appendLine("SingleMainWindow=true")
        appendLine("Categories=Utility;Development;")
    }

    fun macExecScript(spliceBin: Path): String {
        val bin = spliceBin.toString().replace("'", "'\\''")
        return "#!/bin/sh\n$MANAGED_COMMENT\nexec '$bin' launch claude-desktop \"\$@\"\n"
    }

    fun macInfoPlist(hasIcon: Boolean): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(PLIST_DOCTYPE)
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")
        appendLine("  <key>CFBundleName</key><string>$SIDE_BY_SIDE_APP_NAME</string>")
        appendLine("  <key>CFBundleDisplayName</key><string>$SIDE_BY_SIDE_APP_NAME</string>")
        appendLine("  <key>CFBundleIdentifier</key><string>$SIDE_BY_SIDE_WM_CLASS</string>")
        appendLine("  <key>CFBundleExecutable</key><string>$SIDE_BY_SIDE_DIR_NAME</string>")
        appendLine("  <key>CFBundlePackageType</key><string>APPL</string>")
        appendLine("  <key>CFBundleVersion</key><string>1.0</string>")
        if (hasIcon) appendLine("  <key>CFBundleIconFile</key><string>electron.icns</string>")
        appendLine("</dict>")
        appendLine("</plist>")
    }
}

private const val PLIST_DOCTYPE =
    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
        "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">"
private const val XDG_DATA_HOME = "XDG_DATA_HOME"
private const val MANAGED_COMMENT = "# Managed by splice"
private const val MAC_MANAGED_MARKER = ".splice-managed"
private val EXEC_PERMS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_EXECUTE,
)
