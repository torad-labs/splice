import { createResource } from '@shared/lib';
import type { ProjectFilesPayload, ProjectRow, ProjectsPayload } from './types';

/** The project list (GET /api/projects). */
export const projectsStore = createResource<ProjectsPayload>();

/** The OPEN project's own row (GET /api/projects/{id}), read while its detail is shown. */
export const projectStore = createResource<ProjectRow>();

/** The files of ONE project, loaded when a project page is opened. */
export const projectFilesStore = createResource<ProjectFilesPayload>();
