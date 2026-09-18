import { createResource } from '@shared/lib';
import type { ProjectFilesSlice, ProjectsSlice } from './types';

/** The project list. A union with PendingRoute because GET /api/projects does not exist yet
 *  (V4-131), so a page renders the pending empty rather than a mocked repo list. */
export const projectsStore = createResource<ProjectsSlice>();

/** The files of ONE project, loaded when a project page is opened. */
export const projectFilesStore = createResource<ProjectFilesSlice>();
