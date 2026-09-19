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
import { Fault } from '@shared/controls';
import { Empty, Reveal, Strip, StripField } from '@shared/ui';
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

  if (files === undefined && state.error !== null) return <Fault message={state.error} />;
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
            {/* THE BUDGET FITS THE PANE, AND THE PATH CLIPS AT THE OTHER END (M2-31 follow-up).
                The declared-over-content diagnostic reads path 0.41 and head 0.83 here -- both
                UNDER, so there is no column holding more than it needs and no share to move. A
                ratio far below 1 is a column holding a KIND of value no width satisfies, and the
                answer is not a width: path holds 82ch of absolute path and this pane has 43.
                Two things were wrong and neither was a share. FIRST, the widget declared 46ch of
                fields plus a 56px edge holder -- 497px -- inside a 468px pane, so the last 29px
                of `head` was cut by the PANE rather than by its own box, which means cut with no
                ellipsis and no signal that anything was removed. 31 + 12 = 43ch fits (measured at
                9.59px per ch), and `head` now clips inside its own cell and says so.
                SECOND, and this is the one only the capture could find: clipping a path from the
                right throws away the segment that identifies it. These two
                  /home/marcos/Documents/dev/projects/mythos/repo/CLAUDE.md
                  /home/marcos/Documents/dev/projects/mythos/repo/AGENTS.md
                both printed as `/home/marcos/Documents/dev/projects/...` -- two different files,
                one string. The path column now clips at the START (file-view.css carries the
                mechanism), so they read `...v/projects/mythos/repo/CLAUDE.md` and `...AGENTS.md`.
                Taking three ch off path costs only shared prefix now, which is why the budget
                could be fitted to the pane without losing anything. */}
            <StripField w={31} label={S.path} value={file.path} />
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
