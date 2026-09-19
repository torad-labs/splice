// The project entity's HTTP segment. Two routes, both pending V4-131.
//
// The project PAGE needs nothing else from the daemon: its sessions come from @entities/session,
// its turns and cost from @entities/economics and @entities/perf, its compaction scope and
// statusline roots entry from @entities/config, so this slice owns the repo list and the repo's own
// files and lets the page compose the rest. That is why section 6 lists a files route but no
// project-detail route, and none is invented here.
import { pendingOf, request } from '@shared/api';
import { poll } from '@shared/lib';
import { projectFilesStore, projectsStore } from '../model/store';
import type { ProjectFilesPayload, ProjectsPayload } from '../model/types';

/** The v0.4.0 item that will serve the project routes. */
export const PENDING_PROJECTS = 'V4-131';

export async function fetchProjects(): Promise<void> {
  projectsStore.startLoading();
  try {
    projectsStore.setData(await request<ProjectsPayload>('/api/projects'));
  } catch (err) {
    const pending = pendingOf(err, PENDING_PROJECTS);
    if (pending !== null) {
      projectsStore.setData(pending);
      return;
    }
    projectsStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startProjectsPolling(intervalMs = 15000): () => void {
  return poll(fetchProjects, intervalMs);
}

/** One project's instruction and memory files: read when its page opens, never polled, because a
 *  repo's CLAUDE.md does not change under the console often enough to justify a timer. */
export async function fetchProjectFiles(id: string): Promise<void> {
  projectFilesStore.startLoading();
  try {
    projectFilesStore.setData(
      await request<ProjectFilesPayload>(`/api/projects/${encodeURIComponent(id)}/files`),
    );
  } catch (err) {
    const pending = pendingOf(err, PENDING_PROJECTS);
    if (pending !== null) {
      projectFilesStore.setData(pending);
      return;
    }
    projectFilesStore.setError(err instanceof Error ? err.message : String(err));
  }
}
