import { useState } from 'react';
import { providerPage } from '../../lib/login-tab';
import { S } from '../../lib/words-login';
import { Button, Copy } from '../../ui';

/** What a login hands the operator to finish it elsewhere: a device code with the page to enter it on, or a link. */
export function LoginTicket({ code, link }: { code: string | null; link: string | null }) {
  return (
    <>
      {code === null ? null : (
        <div className="code-row">
          <code className="user-code">{code}</code>
          <CopyText text={code} />
        </div>
      )}
      {link === null ? null : providerPage(link) === null ? (
        <span className="hint alert">{S.signInUnavailable}</span>
      ) : (
        <a className="btn go" href={link} target="_blank" rel="noopener noreferrer">
          {code !== null ? S.openVerification : S.openSignIn}
        </a>
      )}
    </>
  );
}

function CopyText({ text }: { text: string }) {
  const [done, setDone] = useState(false);
  return (
    <Button
      small
      onClick={() => {
        void navigator.clipboard.writeText(text).then(() => setDone(true));
      }}
    >
      <Copy />
      {done ? S.copied : S.copy}
    </Button>
  );
}
