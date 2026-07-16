/**
 * Louvain Modularity Maximization
 *
 * Partitions concerns into communities (agents) by maximizing:
 *
 *   Q = (1/2m) Σᵢⱼ [H(i,j) - (kᵢ × kⱼ)/(2m)] × δ(cᵢ, cⱼ)
 *
 * Where:
 *   H(i,j) = coupling score from the Hamiltonian
 *   kᵢ = Σⱼ H(i,j) = total coupling strength of concern i
 *   m  = ½ Σᵢⱼ H(i,j) = total coupling in the system
 *   δ(cᵢ, cⱼ) = 1 if i and j are in the same community
 *
 * Recursive application produces agent/sub-agent hierarchy.
 */

import type { AgentPartition, Concern, CouplingPair, HyperEdge } from "../schemas.js";

// ─── Types ───────────────────────────────────────────────────────────────────

interface Community {
  id: string;
  members: string[]; // concern IDs
}

// ─── Modularity Computation ──────────────────────────────────────────────────

/**
 * Compute the modularity Q for a given partition.
 */
function computeModularity(
  concernIds: string[],
  matrix: CouplingPair[],
  communities: Map<string, string> // concernId → communityId
): number {
  // Total coupling strength
  const m2 = matrix.reduce((sum, p) => sum + Math.max(p.totalH, 0), 0);
  if (m2 === 0) return 0;

  // Node strengths k_i
  const k = new Map<string, number>();
  for (const id of concernIds) {
    const strength = matrix
      .filter((p) => p.i === id || p.j === id)
      .reduce((sum, p) => sum + Math.max(p.totalH, 0), 0);
    k.set(id, strength);
  }

  let Q = 0;
  for (const p of matrix) {
    if (communities.get(p.i) !== communities.get(p.j)) continue;
    const ki = k.get(p.i) ?? 0;
    const kj = k.get(p.j) ?? 0;
    Q += Math.max(p.totalH, 0) - (ki * kj) / m2;
  }

  return Q / m2;
}

/**
 * Compute the modularity gain from moving node `nodeId` to community `targetCommunity`.
 */
function modularityGain(
  nodeId: string,
  targetCommunity: string,
  concernIds: string[],
  matrix: CouplingPair[],
  communities: Map<string, string>
): number {
  const before = computeModularity(concernIds, matrix, communities);

  const trial = new Map(communities);
  trial.set(nodeId, targetCommunity);

  const after = computeModularity(concernIds, matrix, trial);

  return after - before;
}

// ─── Louvain Phase 1: Local Moving ──────────────────────────────────────────

function louvainPhase1(concernIds: string[], matrix: CouplingPair[]): Map<string, string> {
  // Initialize: each concern in its own community
  const communities = new Map<string, string>();
  for (const id of concernIds) {
    communities.set(id, id);
  }

  let improved = true;
  let iterations = 0;
  const maxIterations = 100; // safety bound

  while (improved && iterations < maxIterations) {
    improved = false;
    iterations++;

    for (const nodeId of concernIds) {
      // biome-ignore lint/style/noNonNullAssertion: guaranteed by initialization — all concernIds set on lines 85-87
      const currentCommunity = communities.get(nodeId)!;

      // Find neighboring communities
      const neighborCommunities = new Set<string>();
      for (const p of matrix) {
        // biome-ignore lint/style/noNonNullAssertion: guaranteed — matrix nodes are subset of concernIds, all initialized
        if (p.i === nodeId) neighborCommunities.add(communities.get(p.j)!);
        // biome-ignore lint/style/noNonNullAssertion: guaranteed — matrix nodes are subset of concernIds, all initialized
        if (p.j === nodeId) neighborCommunities.add(communities.get(p.i)!);
      }

      let bestGain = 0;
      let bestCommunity = currentCommunity;

      for (const targetComm of neighborCommunities) {
        if (targetComm === currentCommunity) continue;
        const gain = modularityGain(nodeId, targetComm, concernIds, matrix, communities);
        if (gain > bestGain) {
          bestGain = gain;
          bestCommunity = targetComm;
        }
      }

      if (bestCommunity !== currentCommunity) {
        communities.set(nodeId, bestCommunity);
        improved = true;
      }
    }
  }

  return communities;
}

// ─── Partition Construction ──────────────────────────────────────────────────

/**
 * Build named agent partitions from community assignments.
 */
function buildPartitions(
  _concerns: Concern[],
  communities: Map<string, string>,
  _matrix: CouplingPair[]
): Community[] {
  const grouped = new Map<string, string[]>();
  for (const [nodeId, commId] of communities) {
    if (!grouped.has(commId)) grouped.set(commId, []);
    grouped.get(commId)?.push(nodeId);
  }

  return Array.from(grouped.entries()).map(([commId, members]) => ({
    id: commId,
    members,
  }));
}

/**
 * Generate a descriptive agent name from its member concerns.
 */
function nameAgent(concerns: Concern[], memberIds: string[]): string {
  const members = concerns.filter((c) => memberIds.includes(c.id));

  // Use the dominant concern type
  const typeCounts = new Map<string, number>();
  for (const m of members) {
    typeCounts.set(m.type, (typeCounts.get(m.type) ?? 0) + 1);
  }
  const dominantType = [...typeCounts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? "general";

  // Combine with first concern's name for specificity
  const prefix = members[0]?.name.split(/[\s-]/)[0]?.toLowerCase() ?? "agent";
  return `${prefix}-${dominantType}-agent`;
}

/**
 * Describe the agent's role from its member concerns.
 */
function describeRole(concerns: Concern[], memberIds: string[]): string {
  const members = concerns.filter((c) => memberIds.includes(c.id));
  const descriptions = members.map((m) => m.description);
  return descriptions.join("; ");
}

// ─── Recursive Partitioning ──────────────────────────────────────────────────

const MIN_PARTITION_SIZE = 2; // Don't try to sub-partition fewer than 2 concerns
const SUB_PARTITION_THRESHOLD = 0.05; // Minimum Q improvement to justify sub-agents

/**
 * Recursively partition an agent's concerns into sub-agents if modularity improves.
 */
function trySubPartition(
  partition: AgentPartition,
  allConcerns: Concern[],
  matrix: CouplingPair[],
  hyperedges: HyperEdge[] = []
): AgentPartition {
  if (partition.concerns.length < MIN_PARTITION_SIZE * 2) {
    // Too small to meaningfully sub-partition
    return partition;
  }

  // Filter matrix to only pairs within this partition
  const localMatrix = matrix.filter(
    (p) => partition.concerns.includes(p.i) && partition.concerns.includes(p.j)
  );

  // Filter hyperedges to only those with ALL members in this partition
  const localHyperedges = hyperedges.filter((he) =>
    he.concernIds.every((id) => partition.concerns.includes(id))
  );

  // Use constrained Louvain if hyperedges apply to this partition
  const localForced = buildHyperedgeConstraints(localHyperedges);
  const subCommunities =
    localForced.size > 0
      ? constrainedLouvainPhase1(partition.concerns, localMatrix, localForced)
      : louvainPhase1(partition.concerns, localMatrix);

  // Count distinct communities
  const distinctComms = new Set(subCommunities.values());
  if (distinctComms.size <= 1) return partition; // No split found

  const subQ = computeModularity(partition.concerns, localMatrix, subCommunities);

  if (subQ < SUB_PARTITION_THRESHOLD) return partition; // Not worth splitting

  // Build sub-agent partitions
  const subPartitions = buildPartitions(allConcerns, subCommunities, localMatrix);

  const subAgents = subPartitions.map((sp) => {
    const subPartition: AgentPartition = {
      name: nameAgent(allConcerns, sp.members),
      role: describeRole(allConcerns, sp.members),
      concerns: sp.members,
      internalCohesion: computeModularity(
        sp.members,
        localMatrix,
        new Map(sp.members.map((id) => [id, sp.id]))
      ),
      subAgents: [],
    };

    // Recurse — pass hyperedges through
    return trySubPartition(subPartition, allConcerns, matrix, hyperedges);
  });

  return {
    ...partition,
    concerns: [], // Concerns distributed to sub-agents
    subAgents: subAgents,
  };
}

// ─── Public API ──────────────────────────────────────────────────────────────

export interface PartitionResult {
  agents: AgentPartition[];
  modularity: number;
}

// ─── Hyperedge Pre-Merge ─────────────────────────────────────────────────────
// Before running Louvain, force all concerns in each hyperedge into the same
// initial community. Louvain can move other nodes freely but hyperedge members
// stay together.

function buildHyperedgeConstraints(hyperedges: HyperEdge[]): Map<string, string> {
  // Maps: concernId → forced community ID
  // All concerns in a hyperedge get the same community ID (first concern's ID)
  const forced = new Map<string, string>();

  for (const he of hyperedges) {
    // Sort so the canonical community ID is deterministic
    const sorted = [...he.concernIds].sort();
    // biome-ignore lint/style/noNonNullAssertion: guaranteed — hyperedge concernIds is non-empty by schema
    const communityId = sorted[0]!;

    for (const cId of sorted) {
      // If already forced into a community by another hyperedge, merge them
      const existing = forced.get(cId);
      if (existing && existing !== communityId) {
        // Merge: all concerns from the existing community adopt the new one
        for (const [k, v] of forced) {
          if (v === existing) forced.set(k, communityId);
        }
      }
      forced.set(cId, communityId);
    }
  }

  return forced;
}

function constrainedLouvainPhase1(
  concernIds: string[],
  matrix: CouplingPair[],
  forced: Map<string, string>
): Map<string, string> {
  // Initialize: each concern in its own community UNLESS forced
  const communities = new Map<string, string>();
  for (const id of concernIds) {
    communities.set(id, forced.get(id) ?? id);
  }

  let improved = true;
  let iterations = 0;
  const maxIterations = 100;

  while (improved && iterations < maxIterations) {
    improved = false;
    iterations++;

    for (const nodeId of concernIds) {
      // If this node is part of a hyperedge, it cannot move
      if (forced.has(nodeId)) continue;

      // biome-ignore lint/style/noNonNullAssertion: guaranteed by initialization — all concernIds set on lines 294-296
      const currentCommunity = communities.get(nodeId)!;

      const neighborCommunities = new Set<string>();
      for (const p of matrix) {
        // biome-ignore lint/style/noNonNullAssertion: guaranteed — matrix nodes are subset of concernIds, all initialized
        if (p.i === nodeId) neighborCommunities.add(communities.get(p.j)!);
        // biome-ignore lint/style/noNonNullAssertion: guaranteed — matrix nodes are subset of concernIds, all initialized
        if (p.j === nodeId) neighborCommunities.add(communities.get(p.i)!);
      }

      let bestGain = 0;
      let bestCommunity = currentCommunity;

      for (const targetComm of neighborCommunities) {
        if (targetComm === currentCommunity) continue;
        const gain = modularityGain(nodeId, targetComm, concernIds, matrix, communities);
        if (gain > bestGain) {
          bestGain = gain;
          bestCommunity = targetComm;
        }
      }

      if (bestCommunity !== currentCommunity) {
        communities.set(nodeId, bestCommunity);
        improved = true;
      }
    }
  }

  return communities;
}

/**
 * Partition concerns into an agent hierarchy using Louvain modularity maximization.
 * Respects hyperedges as hard constraints — hyperedge members are never split.
 *
 * Deterministic. Given the same matrix + hyperedges, produces the same partition.
 * No LLM needed.
 */
export function partitionConcerns(
  concerns: Concern[],
  matrix: CouplingPair[],
  hyperedges: HyperEdge[] = []
): PartitionResult {
  const concernIds = concerns.map((c) => c.id);

  // Build hyperedge constraints (forced community assignments)
  const forced = buildHyperedgeConstraints(hyperedges);

  // Phase 1: constrained community detection
  const communities =
    forced.size > 0
      ? constrainedLouvainPhase1(concernIds, matrix, forced)
      : louvainPhase1(concernIds, matrix);

  // Compute global modularity
  const modularity = computeModularity(concernIds, matrix, communities);

  // Build top-level partitions
  const topPartitions = buildPartitions(concerns, communities, matrix);

  // Build agent tree with recursive sub-partitioning
  const agents = topPartitions.map((partition) => {
    const agentPartition: AgentPartition = {
      name: nameAgent(concerns, partition.members),
      role: describeRole(concerns, partition.members),
      concerns: partition.members,
      internalCohesion: computeModularity(
        partition.members,
        matrix,
        new Map(partition.members.map((id) => [id, partition.id]))
      ),
      subAgents: [],
    };

    return trySubPartition(agentPartition, concerns, matrix, hyperedges);
  });

  return { agents, modularity };
}
