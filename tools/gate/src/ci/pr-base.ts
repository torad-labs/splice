/** Select against the tested merge\'s actual base, not the potentially stale event base. */
export function pullRequestBase(root: string, head: string): string {
  const isSha = (value: string) => /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(value);
  if (!isSha(head)) throw new Error("PR planning requires a full head SHA");
  const merge = Bun.spawnSync(["git", "rev-list", "--parents", "-n", "1", "HEAD"], { cwd: root });
  if (merge.exitCode !== 0) throw new Error("Cannot read PR merge candidate");
  const commits = merge.stdout.toString().trim().split(/\s+/);
  if (commits.length !== 3 || !isSha(commits[1]!) || commits[2] !== head) {
    throw new Error("PR candidate is not a two-parent merge of the event's head");
  }
  const base = commits[1]!;
  for (const parent of [base, head]) {
    const exists = Bun.spawnSync(["git", "cat-file", "-e", parent + "^{commit}"], { cwd: root });
    if (exists.exitCode !== 0) throw new Error("PR checkout is missing a merge parent");
  }
  return base;
}
