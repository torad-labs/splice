import { currentCoreFixtures } from "./current-core";
import { currentClientCases, expandedClientInput } from "./current-client/cases";
import { clientCaseIdentity } from "./current-client-profile";
import { apiBatches } from "./current-core-api-witness";
import { coreCaseIdentity, type NamedWitness } from "./current-witness";

type Plan = Pick<NamedWitness, "caseId" | "inputSha256" | "productionEntrypoint">;
/** A well-formed identifier is not proof that a mandatory executable case exists. */
export function plannedWitnessLookup(): (witness: NamedWitness) => boolean {
  const core = new Map<string, Plan>(currentCoreFixtures().map(input => { const plan = coreCaseIdentity(input); return [plan.caseId, plan]; }));
  for (const plan of apiBatches()) core.set(plan.caseId, plan);
  const clients = new Map(currentClientCases.map(input => [input.id, input])), cache = new Map<string, Plan>();
  return witness => {
    let plan = core.get(witness.caseId) ?? cache.get(witness.caseId);
    if (!plan && clients.has(witness.caseId)) { plan = clientCaseIdentity(expandedClientInput(clients.get(witness.caseId), "static-specification")); cache.set(witness.caseId, plan); }
    return !!plan && plan.inputSha256 === witness.inputSha256 && plan.productionEntrypoint === witness.productionEntrypoint;
  };
}
