import { mkdir, realpath, stat } from "node:fs/promises";
import { resolve } from "node:path";
import { homedir } from "node:os";

type SourcePin = { sourceRevision: string; tag: string; repository: string };
async function git(cwd: string, args: string[]): Promise<{ code: number; stdout: string; stderr: string }> {
  const child = Bun.spawn(["git", ...args], { cwd, stdin: "ignore", stdout: "pipe", stderr: "pipe" });
  const timeout = setTimeout(() => child.kill("SIGKILL"), 60_000);
  try { const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]); return { code, stdout, stderr }; }
  finally { clearTimeout(timeout); }
}
/** A fresh checkout obtains the exact public source automatically; existing overrides remain read-only. */
export async function resolveCurrentSource(pin: SourcePin, explicit?: string): Promise<string> {
  if (!/^[a-f0-9]{40}$/.test(pin.sourceRevision) || !/^rust-v\d+\.\d+\.\d+$/.test(pin.tag) || pin.repository !== "https://github.com/openai/codex") throw new Error("invalid official current source bootstrap pin");
  const supplied = explicit ?? process.env.CODEX_REFERENCE_REPO;
  if (supplied) {
    const path = await realpath(supplied), result = await git(path, ["cat-file", "-e", `${pin.sourceRevision}^{commit}`]);
    if (result.code) throw new Error(`reference override lacks pinned ${pin.sourceRevision}; fetch ${pin.tag} from ${pin.repository} or omit --source-repo/CODEX_REFERENCE_REPO for automatic bootstrap`);
    return path;
  }
  const cache = resolve(process.env.XDG_CACHE_HOME ?? resolve(homedir(), ".cache"), "splice/codemode-reference", `${pin.sourceRevision}.git`);
  await mkdir(cache, { recursive: true });
  try { await stat(resolve(cache, "HEAD")); }
  catch {
    const initialized = await git(cache, ["init", "--bare", "--quiet"]);
    if (initialized.code) throw new Error(`cannot initialize current source cache ${cache}: ${initialized.stderr.trim()}`);
  }
  if ((await git(cache, ["cat-file", "-e", `${pin.sourceRevision}^{commit}`])).code !== 0) {
    const fetched = await git(cache, ["fetch", "--depth=1", "--no-tags", `${pin.repository}.git`, `refs/tags/${pin.tag}:refs/tags/${pin.tag}`]);
    if (fetched.code && (await git(cache, ["cat-file", "-e", `${pin.sourceRevision}^{commit}`])).code !== 0) throw new Error(`pinned source bootstrap failed; retry with network access or set CODEX_REFERENCE_REPO to a clone containing ${pin.sourceRevision}: ${fetched.stderr.trim()}`);
  }
  const tag = await git(cache, ["rev-parse", `${pin.tag}^{commit}`]);
  if (tag.code || tag.stdout.trim() !== pin.sourceRevision) throw new Error("official reference tag moved or does not match the frozen source SHA");
  return cache;
}
