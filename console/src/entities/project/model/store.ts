import { createKeyed, createResource } from '@shared/lib';
import type { ProjectFilesPayload, ProjectRow, ProjectsPayload } from './types';

/** The project list (GET /api/projects). */
export const projectsStore = createResource<ProjectsPayload>();

/** The OPEN project's own row (GET /api/projects/{id}), read while its detail is shown, by id: the
 *  detail opened next asks for its own (V4-304). */
export const projectStore = createKeyed<string, ProjectRow>();

/** The files of ONE project (GET /api/projects/{id}/files), loaded when a project page is opened, by
 *  id (V4-304). */
export const projectFilesStore = createKeyed<string, ProjectFilesPayload>();
