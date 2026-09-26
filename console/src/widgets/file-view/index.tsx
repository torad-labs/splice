// A project's instruction and memory files, read-only: one row per file with its kind, its path and
// the head it was read from, and the contents behind a Reveal.
//
// The reveal is not decoration: a CLAUDE.md is paragraphs, so the bytes are not in the DOM until the
// reader asks for them. Nothing here writes: the route is read-only by design.
import { useEffect } from 'react';
import { HeadMark } from '@entities/control-status';
import { fetchProjectFiles, useProjectFiles } from '@entities/project';
import type { ProjectFilesPayload } from '@entities/project';
import { Fault } from '@shared/controls';
import { readFor } from '@shared/lib';
import type { Keyed } from '@shared/lib';
import { Badge, Empty, Reveal } from '@shared/ui';
import { H, S } from './strings';
import './file-view.css';

/** Where the reader looked, as the help of an empty file list. FEATURES.md 4.14 requires the empty
 *  to name the exact directory it searched. */
function lookedIn(data: ProjectFilesPayload): string {
  return data.looked_in.length > 0 ? `${H.lookedIn} ${data.looked_in.join(', ')}` : H.noDirectory;
}

/**
 * `files` is the fixture seam (CONTRACTS.md section 4, the fixture rule): a
 * capture passes the sample payload straight in, so the sample never has to
 * reach the store and the shipped build never carries it. When it is provided
 * this reads nothing.
 */
/** What the view prints: the fixture's files when one is handed in, else the read made for THIS
 *  project and its own failure (V4-304). */
export function filesShown(state: Keyed<string, ProjectFilesPayload>, projectId: string, files?: ProjectFilesPayload): { data: ProjectFilesPayload | null; error: string | null } {
  if (files !== undefined) return { data: files, error: null };
  const { data, error } = readFor(state, projectId);
  return { data, error };
}

export function FileView({ projectId, files }: { projectId: string; files?: ProjectFilesPayload }) {
  const state = useProjectFiles((s) => s);

  useEffect(() => {
    if (files === undefined) void fetchProjectFiles(projectId);
  }, [projectId, files]);

  const shown = filesShown(state, projectId, files);
  if (shown.error !== null) return <Fault message={shown.error} />;
  const data = shown.data;
  if (data === null) return null;
  if (data.files.length === 0) {
    // FEATURES.md 4.14: the empty names the setting when the client's memory is off, and always
    // names the directories the reader looked in.
    return <Empty text={data.auto_memory_enabled === false ? S.memoryOff : S.noFiles} source={lookedIn(data)} />;
  }

  return (
    <ul className="myx-fv" aria-label={S.files}>
      {data.files.map((file) => (
        <li className="myx-fv-row" key={`${file.kind}:${file.path}:${file.head ?? ''}`}>
          <Badge tone="neutral" quiet>{file.kind === 'memory' ? S.memory : S.instructions}</Badge>
          {/* A path identifies by its tail, so it clips at its start (file-view.css). */}
          <span className="myx-fv-path" title={file.path}><span className="myx-fv-path-text">{file.path}</span></span>
          <span className="myx-fv-head">{file.head === null ? S.repo : <HeadMark head={file.head} />}</span>
          <span className="myx-fv-text-cell">
            <Reveal label={S.contents}>
              <pre className="myx-fv-text">{file.text}</pre>
            </Reveal>
          </span>
        </li>
      ))}
    </ul>
  );
}
