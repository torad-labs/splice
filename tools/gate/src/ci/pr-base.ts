/** Validate the exact GitHub PR merge parents before selecting against its base. */
export function pullRequestBase(root: string, base: string, head: string): string {
  const isSha = (value: string) => /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(value);
  if (!isSha(base) || !isSha(head)) throw new Error("PR planning requires full base and head SHAs");
  const merge = Bun.spawnSync(["git", "rev-list", "--parents", "-n", "1", "HEAD"], { cwd: root });
  if (merge.exitCode !== 0) throw new Error("Cannot read PR merge candidate");
  const commits = merge.stdout.toString().trim().split(/\s+/);
  if (commits.length !== 3 || commits[1] !== base || commits[2] !== head) {
    throw new Error("PR candidate does not match the event's two merge parents");
  }
  for (const parent of [base, head]) {
    const exists = Bun.spawnSync(["git", "cat-file", "-e", parent + "^{commit}"], { cwd: root });
    if (exists.exitCode !== 0) throw new Error("PR checkout is missing a merge parent");
  }
  return base;
}
