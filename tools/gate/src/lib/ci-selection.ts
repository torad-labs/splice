import { readFileSync } from "node:fs";
import { join } from "node:path";
import { gradleModules, moduleOf, readLadder, readModuleGraph } from "../commands/hook.ts";
import { prePushScope } from "./prepush-scope.ts";
import { repoScope } from "./scope.ts";
import { declaredProjectDeps } from "./project-deps.ts";

/** CI must include test/fixture consumers and unrestricted modules such as App. */
export function ciSelection(root: string, changed: readonly string[]) {
  const modules = gradleModules(root);
  const declared = readModuleGraph(root);
  const graph = new Map(modules.map((module) => [module.path, [...new Set([
    ...(declared.get(module.path) ?? []),
    ...declaredProjectDeps(readFileSync(join(root, module.dir, "build.gradle.kts"), "utf8"), true),
  ])]]));
  const scope = repoScope();
  const executionInputs = ["tools/gate/ci.init.gradle", "tools/gate/src/ci/**", "tools/gate/src/ci-coverage/**", "tools/gate/src/commands/ci.ts", "tools/gate/config/ci-*.json", ".github/workflows/ci.yml", ".github/workflows/coverage.yml", ".github/actions/ci-setup/**"];
  return prePushScope({
    scope: { ...scope, full: [...scope.full, ...executionInputs.map((glob) => ({ glob, why: "changes how CI executes module checks" }))] },
    legs: readLadder(root), modules: modules.map((module) => module.path),
    moduleOf: (file) => moduleOf(modules, file), changed, graph,
  });
}
