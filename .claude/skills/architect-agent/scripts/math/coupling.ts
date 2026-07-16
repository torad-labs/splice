/**
 * Hamiltonian Coupling Matrix
 *
 * H(cᵢ, cⱼ) = w_data × DataCoupling(i,j)
 *            + w_state × StateCoupling(i,j)
 *            + w_domain × DomainCoupling(i,j)
 *            - w_boundary × BoundaryCost(i,j)
 *
 * DataCoupling, StateCoupling, BoundaryCost are deterministic.
 * DomainCoupling requires LLM evaluation — injected as a parameter.
 */

import type { Concern, CouplingPair, CouplingWeights, HyperEdge } from "../schemas.js";

// ─── Deterministic Coupling Functions ────────────────────────────────────────

/**
 * DataCoupling: How many types flow between two concerns.
 * Normalized to [0, 1] by dividing by total unique types across both concerns.
 */
export function computeDataCoupling(a: Concern, b: Concern): number {
  const aReadsFromB = a.reads.filter((t) => b.writes.includes(t)).length;
  const bReadsFromA = b.reads.filter((t) => a.writes.includes(t)).length;
  const flow = aReadsFromB + bReadsFromA;

  if (flow === 0) return 0;

  const allTypes = new Set([...a.reads, ...a.writes, ...b.reads, ...b.writes]);
  return Math.min(flow / allTypes.size, 1);
}

/**
 * StateCoupling: Do these concerns share a state requirement?
 * Same state = 1, adjacent (session/persistent) = 0.5, mismatch = 0.
 */
export function computeStateCoupling(a: Concern, b: Concern): number {
  if (a.state === b.state) return 1;

  // Stateless pairs with anything stateful: no coupling
  if (a.state === "stateless" || b.state === "stateless") return 0;

  // session + persistent: moderate coupling (shared transaction context possible)
  return 0.5;
}

/**
 * BoundaryCost: The overhead of separating two concerns into different agents.
 *
 * High cost (hard to separate):
 * - Both touch the same external system (need shared connection/auth)
 * - One reads what the other writes AND requires synchronous coordination
 *
 * Low cost (easy to separate):
 * - Events (fire-and-forget)
 * - No shared external systems
 * - No data flow between them
 */
export function computeBoundaryCost(a: Concern, b: Concern): number {
  let cost = 0;

  // Shared external systems increase separation cost
  const sharedSystems = a.externalSystems.filter((s) => b.externalSystems.includes(s));
  if (sharedSystems.length > 0) {
    cost += 0.4 * Math.min(sharedSystems.length / 3, 1);
  }

  // Synchronous data dependency increases cost
  const hasDataFlow =
    a.reads.some((t) => b.writes.includes(t)) || b.reads.some((t) => a.writes.includes(t));

  // Events are async by definition — low boundary cost
  const eitherIsEvent = a.type === "event" || b.type === "event";

  if (hasDataFlow && !eitherIsEvent) {
    cost += 0.4;
  }

  // Shared invariants create implicit coupling across boundaries
  const sharedInvariants = a.invariants.filter((inv) =>
    b.invariants.some(
      (bInv) =>
        // Simple containment check — the LLM DomainCoupling handles semantics
        bInv.toLowerCase().includes(inv.toLowerCase().slice(0, 20)) ||
        inv.toLowerCase().includes(bInv.toLowerCase().slice(0, 20))
    )
  );
  if (sharedInvariants.length > 0) {
    cost += 0.2;
  }

  return Math.min(cost, 1);
}

// ─── Matrix Assembly ─────────────────────────────────────────────────────────

/**
 * Default weights. Tunable per domain.
 * Data coupling dominates because it's the strongest signal for co-location.
 * Boundary cost is subtracted — it penalizes separation.
 */
export const DEFAULT_WEIGHTS: CouplingWeights = {
  data: 0.35,
  state: 0.2,
  domain: 0.3,
  boundary: 0.15,
};

export interface DomainCouplingMap {
  /** Key: "concernId_i:concernId_j" (sorted alphabetically), Value: [0, 1] */
  [pairKey: string]: number;
}

function pairKey(i: string, j: string): string {
  return [i, j].sort().join(":");
}

/**
 * Build the full coupling matrix.
 *
 * @param concerns - Extracted concerns from DECOMPOSE
 * @param domainCouplings - LLM-evaluated semantic proximity (injected)
 * @param weights - Hamiltonian weight configuration
 * @returns Array of coupling pairs with individual and total scores
 */
export function buildCouplingMatrix(
  concerns: Concern[],
  domainCouplings: DomainCouplingMap,
  weights: CouplingWeights = DEFAULT_WEIGHTS
): CouplingPair[] {
  const pairs: CouplingPair[] = [];

  for (let x = 0; x < concerns.length; x++) {
    for (let y = x + 1; y < concerns.length; y++) {
      const a = concerns[x];
      const b = concerns[y];

      const dataCoupling = computeDataCoupling(a, b);
      const stateCoupling = computeStateCoupling(a, b);
      const domainCoupling = domainCouplings[pairKey(a.id, b.id)] ?? 0;
      const boundaryCost = computeBoundaryCost(a, b);

      const totalH =
        weights.data * dataCoupling +
        weights.state * stateCoupling +
        weights.domain * domainCoupling -
        weights.boundary * boundaryCost;

      pairs.push({
        i: a.id,
        j: b.id,
        dataCoupling: round(dataCoupling),
        stateCoupling: round(stateCoupling),
        domainCoupling: round(domainCoupling),
        boundaryCost: round(boundaryCost),
        totalH: round(totalH),
      });
    }
  }

  return pairs;
}

function round(n: number, decimals = 4): number {
  const f = 10 ** decimals;
  return Math.round(n * f) / f;
}

/**
 * Extract the H score between two concerns from a computed matrix.
 */
export function getH(matrix: CouplingPair[], i: string, j: string): number {
  const key = pairKey(i, j);
  const pair = matrix.find((p) => pairKey(p.i, p.j) === key);
  return pair?.totalH ?? 0;
}

// ─── Hyperedge Detection ─────────────────────────────────────────────────────
// Multi-way constraints that bind 3+ concerns. Deterministic — no LLM needed.
// The Hamiltonian handles pairwise. Hyperedges handle what pairwise can't express.

/**
 * Detect hyperedges — multi-way constraints binding 2+ concerns.
 *
 * Five detection rules, ordered by confidence:
 * 0. Explicit atomicity signals from DECOMPOSE (highest, 2+ concerns)
 * 1. shared_transaction: 3+ concerns writing to the same persistent type
 * 2. shared_invariant: same invariant text across 3+ concerns
 * 3. shared_system: 3+ concerns touching the same external system
 * 4. shared_type_cluster: 4+ concerns all reading/writing the same type
 *
 * Rules 1-4 are heuristic fallbacks. Rule 0 (atomicity) is the strongest signal.
 * Deduplication: if rule 0 already covers a concern set, heuristic rules skip it.
 */
export function detectHyperedges(concerns: Concern[]): HyperEdge[] {
  const edges: HyperEdge[] = [];
  let edgeCounter = 0;

  // 0. Explicit atomicity signals from DECOMPOSE (highest confidence)
  // The decomposer extracts structured atomicity fields from spec/rejection text.
  // Same group ID = same hyperedge. These are the strongest signal.
  const atomicGroups = new Map<string, { ids: string[]; type: string; signal: string }>();
  for (const c of concerns) {
    if (c.atomicity) {
      const key = c.atomicity.group;
      if (!atomicGroups.has(key)) {
        atomicGroups.set(key, { ids: [], type: c.atomicity.type, signal: c.atomicity.signal });
      }
      atomicGroups.get(key)?.ids.push(c.id);
    }
  }
  for (const [group, data] of atomicGroups) {
    const unique = [...new Set(data.ids)];
    if (unique.length >= 2) {
      // Atomicity signals work with 2+ (lower threshold than heuristic)
      const typeMap: Record<string, HyperEdge["type"]> = {
        transaction: "shared_transaction",
        invariant: "shared_invariant",
        ordering: "shared_invariant", // Ordering constraints are invariants on sequence
      };
      edges.push({
        id: `he-atom-${edgeCounter++}`,
        type: typeMap[data.type] ?? "shared_invariant",
        concernIds: unique,
        weight: data.type === "transaction" ? 1.0 : 0.9, // Explicit signals are near-absolute
        reason: `Atomicity group "${group}" (${data.type}): ${data.signal}`,
      });
    }
  }

  // 1. Shared transactions: 3+ persistent concerns writing same type (heuristic fallback)
  const persistentWriters = new Map<string, string[]>();
  for (const c of concerns) {
    if (c.state === "persistent") {
      for (const w of c.writes) {
        if (!persistentWriters.has(w)) persistentWriters.set(w, []);
        persistentWriters.get(w)?.push(c.id);
      }
    }
  }
  for (const [typeName, ids] of persistentWriters) {
    const unique = [...new Set(ids)];
    if (unique.length >= 3) {
      edges.push({
        id: `he-txn-${edgeCounter++}`,
        type: "shared_transaction",
        concernIds: unique,
        weight: 1.0, // Never split — atomic
        reason: `${unique.length} concerns write to "${typeName}" with persistent state — atomic transaction`,
      });
    }
  }

  // 2. Shared invariants: same invariant text across 3+ concerns
  // Normalize invariants for comparison (lowercase, trim, first 50 chars)
  const invariantMap = new Map<string, Set<string>>();
  for (const c of concerns) {
    for (const inv of c.invariants) {
      const key = inv.toLowerCase().trim().slice(0, 50);
      if (!invariantMap.has(key)) invariantMap.set(key, new Set());
      invariantMap.get(key)?.add(c.id);
    }
  }
  for (const [invKey, idSet] of invariantMap) {
    const ids = [...idSet];
    if (ids.length >= 3) {
      edges.push({
        id: `he-inv-${edgeCounter++}`,
        type: "shared_invariant",
        concernIds: ids,
        weight: 0.8,
        reason: `${ids.length} concerns share invariant: "${invKey}"`,
      });
    }
  }

  // 3. Shared external systems: 3+ concerns touching same system
  const systemMap = new Map<string, Set<string>>();
  for (const c of concerns) {
    for (const sys of c.externalSystems ?? []) {
      if (!systemMap.has(sys)) systemMap.set(sys, new Set());
      systemMap.get(sys)?.add(c.id);
    }
  }
  for (const [sys, idSet] of systemMap) {
    const ids = [...idSet];
    if (ids.length >= 3) {
      edges.push({
        id: `he-sys-${edgeCounter++}`,
        type: "shared_system",
        concernIds: ids,
        weight: 0.6,
        reason: `${ids.length} concerns share external system: ${sys}`,
      });
    }
  }

  // 4. Shared type cluster: 3+ concerns all reading OR writing the same type
  const typeUsers = new Map<string, Set<string>>();
  for (const c of concerns) {
    for (const t of [...c.reads, ...c.writes]) {
      if (!typeUsers.has(t)) typeUsers.set(t, new Set());
      typeUsers.get(t)?.add(c.id);
    }
  }
  for (const [typeName, idSet] of typeUsers) {
    const ids = [...idSet];
    // Only if 4+ concerns cluster on a type — 3 is too common to be meaningful
    if (ids.length >= 4) {
      edges.push({
        id: `he-type-${edgeCounter++}`,
        type: "shared_type_cluster",
        concernIds: ids,
        weight: 0.5,
        reason: `${ids.length} concerns converge on type "${typeName}"`,
      });
    }
  }

  // Deduplicate: if a heuristic edge's concerns are a subset of an atomicity edge, remove it
  const deduped: HyperEdge[] = [];
  for (const edge of edges) {
    const isDuplicate = edges.some(
      (other) =>
        other.id !== edge.id &&
        other.weight > edge.weight &&
        edge.concernIds.every((id) => other.concernIds.includes(id))
    );
    if (!isDuplicate) deduped.push(edge);
  }

  return deduped;
}
