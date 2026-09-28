// V4-400: an instruction file the preview refuses is never saved. Marlin's walk of V4-348 on bb54736ea:
// Save stayed enabled beside the refusal and wrote the missing path into splice.toml. Both halves are
// held: the console disables Save while the preview refuses, and the daemon refuses the PUT itself.
import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset — the global setup did not start the stack`);
  return value;
}

test('Save is disabled while the preview refuses the file, and a PUT is refused with its sentence', async ({ page, request }) => {
  const base = env('CONSOLE_E2E_BASE');
  const key = env('CONSOLE_E2E_KEY');
  const config = env('CONSOLE_E2E_CONFIG');
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  const puts: string[] = [];
  page.on('request', (sent) => {
    if (sent.method() === 'PUT' && new URL(sent.url()).pathname === '/api/topology') puts.push(sent.url());
  });
  await page.goto(`${base}/#/settings`);
  const section = page.locator('.myx-settings-section').filter({ has: page.getByRole('heading', { name: 'Instructions', exact: true }) });
  await section.getByRole('button', { name: 'Add command instructions' }).click();
  await section.getByRole('combobox', { name: 'Instruction source' }).click();
  await section.getByRole('option', { name: 'Use file', exact: true }).click();
  await section.getByRole('textbox', { name: 'Instruction file' }).fill('v4400-missing-instructions.md');
  const preview = section.getByRole('region', { name: 'Preview' });
  await expect(preview).toContainText('instruction file does not exist');
  const save = section.getByRole('button', { name: 'Save instructions' });
  await expect(save).toBeDisabled();
  expect(puts, 'a disabled Save sent nothing').toHaveLength(0);

  const before = readFileSync(config, 'utf8');
  const read = await request.get(`${base}/api/topology`, { headers: { Authorization: `Bearer ${key}` } });
  const topology = (await read.json() as { topology: { heads: Record<string, Record<string, unknown>> } }).topology;
  const [head] = Object.keys(topology.heads);
  topology.heads[head] = { ...topology.heads[head], system_prompt_file: 'v4400-missing-instructions.md' };
  delete topology.heads[head].system_prompt;
  const put = await request.put(`${base}/api/topology`, {
    headers: { Authorization: `Bearer ${key}` }, data: { topology },
  });
  const answer = await put.json() as { ok: boolean; findings: { path: string; message: string }[] };
  expect(answer.ok).toBe(false);
  expect(answer.findings).toEqual([
    { path: `heads.${head}.system_prompt_file`, message: 'instruction file does not exist' },
  ]);
  expect(readFileSync(config, 'utf8'), 'the refused PUT left splice.toml byte-identical').toBe(before);

  await section.getByRole('textbox', { name: 'Instruction file' }).fill('');
  await expect(save).toBeDisabled();
});
