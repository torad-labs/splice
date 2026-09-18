// Head editing as pure data over the topology entity's parsed document.
//
// Every function here takes the parsed topology and returns a NEW one, or a list of findings; none
// of them touches the network, the store or the DOM. That is what makes the form testable without
// a renderer, and it is also the honest shape of the operation: the console does not edit a head,
// it edits the DOCUMENT that describes the head and hands the whole document to the daemon's
// writer, which is the only thing that knows how to keep the operator's comments.
//
// The head key IS the topology key (`[heads.<key>]`), and the daemon resolves a head by that key
// or by its wrapper command (`Topology.resolveHeadKeys`), so the key is the one field that cannot
// be edited in place — renaming it would silently orphan every session bound to the old name.

export interface HeadRow {
  key: string;
  provider: string;
  port: string;
  discoveryPrefix: string;
  pinnedModel: string;
  contextWindow: string;
  models: number;
}

export interface HeadDraft {
  key: string;
  provider: string;
  port: string;
  discoveryPrefix: string;
  pinnedModel: string;
}

export interface HeadFinding {
  /** The draft field the finding is about, so the form can mark the input. */
  field: keyof HeadDraft;
  message: string;
}

function asTable(value: unknown): Record<string, unknown> {
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return {};
}

function asText(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}

export const EMPTY_DRAFT: HeadDraft = { key: '', provider: '', port: '', discoveryPrefix: '', pinnedModel: '' };

/** The heads the document declares, in key order. */
export function headRows(topology: Record<string, unknown>): HeadRow[] {
  return Object.entries(asTable(topology.heads))
    .map(([key, raw]) => {
      const head = asTable(raw);
      const models = head.models;
      return {
        key,
        provider: asText(head.provider),
        port: asText(head.port),
        discoveryPrefix: asText(head.discovery_prefix),
        pinnedModel: asText(head.pinned_model),
        contextWindow: asText(head.context_window),
        models: Array.isArray(models) ? models.length : 0,
      };
    })
    .sort((left, right) => left.key.localeCompare(right.key));
}

/** The providers a head may name, in key order. */
export function providerKeys(topology: Record<string, unknown>): string[] {
  return Object.keys(asTable(topology.providers)).sort();
}

function withHeads(
  topology: Record<string, unknown>,
  heads: Record<string, unknown>,
): Record<string, unknown> {
  return { ...topology, heads };
}

/** Set one field on one head. `key` itself is not settable — see the file header. */
export function withHeadField(
  topology: Record<string, unknown>,
  key: string,
  field: keyof Omit<HeadRow, 'key' | 'models'>,
  value: string,
): Record<string, unknown> {
  const heads = asTable(topology.heads);
  const head = asTable(heads[key]);
  const patch: Record<string, unknown> = { ...head, [field]: value };
  return withHeads(topology, { ...heads, [key]: patch });
}

/**
 * Drop a head from the document. This is the console's "disable": the daemon has no enabled flag,
 * so a head that should not run is a head that is not declared — and the write is backed up first,
 * which is what makes it a reversible edit rather than a deletion.
 */
export function withoutHead(topology: Record<string, unknown>, key: string): Record<string, unknown> {
  const heads = Object.fromEntries(
    Object.entries(asTable(topology.heads)).filter(([name]) => name !== key),
  );
  return withHeads(topology, heads);
}

/** Add a head from the draft. The caller must have validated it first. */
export function withNewHead(topology: Record<string, unknown>, draft: HeadDraft): Record<string, unknown> {
  const heads = asTable(topology.heads);
  const port = Number.parseInt(draft.port, 10);
  return withHeads(topology, {
    ...heads,
    [draft.key]: {
      provider: draft.provider,
      port: Number.isFinite(port) ? port : draft.port,
      discovery_prefix: draft.discoveryPrefix,
      pinned_model: draft.pinnedModel,
    },
  });
}

/**
 * Everything wrong with a new head, named per field.
 *
 * The port check is the one that earns this function: the daemon maps ports to the heads that
 * share them and refuses the whole topology at boot on a collision (`Topology.portCollisions`),
 * which an operator discovers only after a draining restart. Catching it here costs nothing.
 */
export function validateNewHead(
  draft: HeadDraft,
  topology: Record<string, unknown>,
): HeadFinding[] {
  const findings: HeadFinding[] = [];
  const rows = headRows(topology);
  const providers = providerKeys(topology);

  if (draft.key.trim() === '') {
    findings.push({ field: 'key', message: 'a head needs a key' });
  } else if (rows.some((row) => row.key === draft.key.trim())) {
    findings.push({ field: 'key', message: `head ${draft.key.trim()} is already declared` });
  }

  if (draft.provider.trim() === '') {
    findings.push({ field: 'provider', message: 'a head needs a provider' });
  } else if (!providers.includes(draft.provider.trim())) {
    findings.push({
      field: 'provider',
      message: `no [providers.${draft.provider.trim()}] in this topology`,
    });
  }

  const port = Number.parseInt(draft.port, 10);
  if (draft.port.trim() === '' || !Number.isFinite(port)) {
    findings.push({ field: 'port', message: 'a head needs a numeric port' });
  } else {
    const clash = rows.find((row) => Number.parseInt(row.port, 10) === port);
    if (clash !== undefined) {
      findings.push({ field: 'port', message: `port ${port} is already ${clash.key}` });
    }
  }

  if (draft.discoveryPrefix.trim() === '') {
    findings.push({ field: 'discoveryPrefix', message: 'a head needs a discovery prefix' });
  }
  if (draft.pinnedModel.trim() === '') {
    findings.push({ field: 'pinnedModel', message: 'a head needs a pinned model' });
  }

  return findings;
}
