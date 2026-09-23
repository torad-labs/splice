import { projectFilesStore, projectStore, projectsStore } from './model/store';

export {
  fetchProject,
  fetchProjectFiles,
  fetchProjects,
  startProjectPolling,
  startProjectsPolling,
} from './api';
export type {
  ProjectCompactionRule,
  ProjectFile,
  ProjectFileKind,
  ProjectFilesPayload,
  ProjectRow,
  ProjectStatuslineRoot,
  ProjectsPayload,
  TrustedRootEntry,
} from './model/types';
export const useProjects = projectsStore.use;
export const useProject = projectStore.use;
export const useProjectFiles = projectFilesStore.use;
