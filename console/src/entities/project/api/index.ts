// The project entity's HTTP segment: the three project routes ProjectsRoutes serves (V4-131): the
// repo list, one repo's own row and the repo's own files. A 404 on any of them is a failure to
// report, never "not built": the routes are served, and the detail route answers 404 for a root
// the daemon has not seen, with a sentence that says so.
import { request } from '@shared/api';
import { poll } from '@shared/lib';
import { projectFilesStore, projectStore, projectsStore } from '../model/store';
import type { ProjectFilesPayload, ProjectRow, ProjectsPayload } from '../model/types';

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export async function fetchProjects(): Promise<void> {
  projectsStore.startLoading();
  try {
    projectsStore.setData(await request<ProjectsPayload>('/api/projects'));
  } catch (err) {
    projectsStore.setError(messageOf(err));
  }
}

export function startProjectsPolling(intervalMs = 15000): () => void {
  return poll(fetchProjects, intervalMs);
}

/** One project's own row (GET /api/projects/{id}): the counts the daemon computes for that root
 *  alone, read fresh while its detail is open. */
export async function fetchProject(id: string): Promise<void> {
  projectStore.startLoading();
  try {
    projectStore.setData(await request<ProjectRow>(`/api/projects/${encodeURIComponent(id)}`));
  } catch (err) {
    projectStore.setError(messageOf(err));
  }
}

/** Polls one project's row while its detail is open, at the list's own cadence. */
export function startProjectPolling(id: string, intervalMs = 15000): () => void {
  return poll(() => fetchProject(id), intervalMs);
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
    projectFilesStore.setError(messageOf(err));
  }
}
