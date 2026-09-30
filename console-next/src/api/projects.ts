// The project reads: the repo list, one repo's own row and its own files. A 404 on the row is a root the daemon has not
// seen, reported with the daemon's sentence.
import { useQuery } from '@tanstack/react-query';
import { read } from './queries';
import type { ProjectFilesPayload, ProjectRow, ProjectsPayload } from '../types/projects';

export const projectsKey = ['projects'] as const;
export const PROJECTS_POLL_MS = 15_000;

export const projectPath = (id: string): string => `/api/projects/${encodeURIComponent(id)}`;

export const useProjects = () => useQuery(read<ProjectsPayload>(projectsKey, '/api/projects', { refetchInterval: PROJECTS_POLL_MS }));
export const useProject = (id: string) => useQuery(read<ProjectRow>([...projectsKey, 'row'], projectPath(id), { refetchInterval: PROJECTS_POLL_MS }));

/** A repo's instruction and memory files: read when its page opens, never polled. */
export const useProjectFiles = (id: string) => useQuery(read<ProjectFilesPayload>([...projectsKey, 'files'], `${projectPath(id)}/files`, { refetchInterval: false }));

