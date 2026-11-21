// The modes settings.gradle.kts includes by listing a folder, not by naming them (modes/README.md): every folder under
// modes/ that holds a mode.toml is the module :modes-<folder>. The gate reads modules from settings.gradle.kts, so it
// lists the folder the same way, and a mode is a module here the moment its folder exists.
import { existsSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

/** The folder names under [buildRoot]/modes that hold a mode.toml, sorted as settings.gradle.kts sorts them. */
export function modeFolders(buildRoot: string): string[] {
  const modes = join(buildRoot, "modes");
  if (!existsSync(modes)) return [];
  return readdirSync(modes)
    .filter((name) => statSync(join(modes, name)).isDirectory() && existsSync(join(modes, name, "mode.toml")))
    .sort();
}
