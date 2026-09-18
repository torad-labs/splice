// A project's instruction and memory files, read-only: a strip per file with
// its path and the head it was read from, and the contents behind a Reveal.
//
// The reveal is not decoration. The world's copy rule allows no paragraph on a
// page except an honest empty, and a CLAUDE.md is paragraphs, so the bytes are
// not in the DOM until the reader asks for them (CONTRACTS.md section 4 of the
// brief). Nothing here writes: the route is read-only by design.
import { useEffect } from 'react';
import { fetchProjectFiles, useProjectFiles } from '@entities/project';
import type { ProjectFilesPayload, ProjectFilesSlice } from '@entities/project';
import { Empty, ErrorNote, Reveal, Strip, StripField } from '@shared/ui';
import { S } from './strings';
import './file-view.css';

/** Where the reader looked, printed as the source of an empty file list. */
function lookedIn(data: ProjectFilesPayload): string {
  return data.looked_in.length > 0 ? data.looked_in.join(' ') : 'no directory reported';
}

/**
 * `files` is the fixture seam (CONTRACTS.md section 4, the fixture rule): a
 * capture passes the sample payload straight in, so the sample never has to
 * reach the store and the shipped build never carries it. When it is provided
 * this reads nothing.
 */
export function FileView({ projectId, files }: { projectId: string; files?: ProjectFilesSlice }) {
  const state = useProjectFiles((s) => s);

  useEffect(() => {
    if (files === undefined) void fetchProjectFiles(projectId);
  }, [projectId, files]);

  if (files === undefined && state.error !== null) return <ErrorNote message={state.error} />;
  const data = files ?? state.data;
  if (data === undefined || data === null) return null;
  if ('pending' in data) {
    return <Empty text="project files not routed yet" source="row V4-131" />;
  }
  if (data.files.length === 0) {
    // FEATURES.md 4.14: the empty names the setting when the client's memory is
    // off, and always names the directories the reader looked in.
    const off = data.auto_memory_enabled === false;
    return (
      <Empty
        text={off ? 'client memory is switched off' : 'no instruction or memory files'}
        source={lookedIn(data)}
      />
    );
  }

  return (
    <div className="myx-fv">
      {data.files.map((file) => (
        <div className="myx-fv-row" key={`${file.kind}:${file.path}:${file.head ?? ''}`}>
          <Strip edge="grey" edgeLabel={file.kind === 'memory' ? S.memory : S.instructions} ariaLabel={file.path}>
            <StripField w={34} label={S.path} value={file.path} />
            <StripField w={12} label={S.head} value={file.head ?? S.repo} />
          </Strip>
          <Reveal label={S.contents}>
            <pre className="myx-fv-text">{file.text}</pre>
          </Reveal>
        </div>
      ))}
    </div>
  );
}
