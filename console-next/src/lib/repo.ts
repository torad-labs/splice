// A repository as a person names it. The daemon reports the git root and, when it can, the remote it was cloned from; the
// remote's name is the repo's own ("torad-labs/splice" is splice), the folder is only where it happens to sit.
const GENERIC = new Set(['repo', 'src', 'code', 'app', 'project', 'checkout']);

const segments = (path: string): string[] => path.split('/').filter((part) => part !== '');

/** The repo's name: the last piece of its remote when there is one, else its folder, and for a folder that says nothing
 *  ("repo", "src") the folder beside its parent, so `.../mythos/repo` reads `mythos/repo`, never `repo`. */
export function repoNameOf(root: string, remote?: string | null): string {
  const fromRemote = remote === undefined || remote === null ? undefined : /([^/:]+?)(?:\.git)?\/*$/.exec(remote.trim())?.[1];
  if (fromRemote !== undefined && fromRemote !== '') return fromRemote;
  const parts = segments(root);
  const last = parts.at(-1) ?? root;
  const parent = parts.at(-2);
  return parent !== undefined && GENERIC.has(last.toLowerCase()) ? `${parent}/${last}` : last;
}
