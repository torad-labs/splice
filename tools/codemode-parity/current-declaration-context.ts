import type { CurrentInventory, SourceUnit } from "./current-inventory";

// Independently reviewed against immutable rust-v0.165.0 Git objects. These are
// transparent Rust aliases, trait accounting containers, or bodyless signatures.
// A source-hash change requires renewed review; names or kinds alone grant nothing.
const reviewedRevision = "360319d8dee62d87e2d59fc0c21805d79519cfed";
type DeclarationProof = {
  id: string; file: string; kind: string; sha256: string; fileSha256: string;
  executableMembers: Array<{ id: string; sha256: string }>;
};
const reviewed: DeclarationProof[] = [
  {
    "id": "codex-rs/analytics/src/facts.rs#trait_item@6881",
    "file": "codex-rs/analytics/src/facts.rs",
    "kind": "trait_item",
    "sha256": "8716587fab6c6e6c2c8ce7cb1ba4ea760ffedc801d485d8ea392b43f6f3e66db",
    "fileSha256": "384eb8c3b0a64e38fb3fa917cda31fb4b0ab6d54ef2bd816d3b3af81af3811d9",
    "executableMembers": []
  },
  {
    "id": "codex-rs/code-mode-protocol/src/session.rs#function_signature_item@5080",
    "file": "codex-rs/code-mode-protocol/src/session.rs",
    "kind": "function_signature_item",
    "sha256": "2c0ef505f77f1a9d95d31cac6be065e4514c8f00d0cd2842475d16cc89c62505",
    "fileSha256": "fe20ee15f845c03576a7d77cc88fc4f8c912403e8e42c2c2fe91ade01ffd5aac",
    "executableMembers": []
  },
  {
    "id": "codex-rs/code-mode-protocol/src/session.rs#function_signature_item@5775",
    "file": "codex-rs/code-mode-protocol/src/session.rs",
    "kind": "function_signature_item",
    "sha256": "38061524371cd206564a5cff7a0f89b729954dc2b383a58dc4400cf48466074d",
    "fileSha256": "fe20ee15f845c03576a7d77cc88fc4f8c912403e8e42c2c2fe91ade01ffd5aac",
    "executableMembers": []
  },
  {
    "id": "codex-rs/code-mode-protocol/src/session.rs#trait_item@4558",
    "file": "codex-rs/code-mode-protocol/src/session.rs",
    "kind": "trait_item",
    "sha256": "aea446664c4c46e5e155bbc89c90d095e0e4bc8e857f82d47ddf9c11176589ba",
    "fileSha256": "fe20ee15f845c03576a7d77cc88fc4f8c912403e8e42c2c2fe91ade01ffd5aac",
    "executableMembers": []
  },
  {
    "id": "codex-rs/code-mode-protocol/src/session.rs#trait_item@5567",
    "file": "codex-rs/code-mode-protocol/src/session.rs",
    "kind": "trait_item",
    "sha256": "85c06a5328d7a6e78c1a9c2a96081226232e3501085676f6303f00350c917d17",
    "fileSha256": "fe20ee15f845c03576a7d77cc88fc4f8c912403e8e42c2c2fe91ade01ffd5aac",
    "executableMembers": [
      {
        "id": "codex-rs/code-mode-protocol/src/session.rs#function_item@5702",
        "sha256": "22cbe667993d7671886cd6110c6f132f1035ee89e527ee9a1b5601501bf22890"
      },
      {
        "id": "codex-rs/code-mode-protocol/src/session.rs#function_item@6083",
        "sha256": "a9c3b2480964004587d1f4a9ad47ecd6d4ce0c3b41789ff0b2665ccf4a29b69d"
      }
    ]
  },
  {
    "id": "codex-rs/code-mode-protocol/src/session.rs#type_item@480",
    "file": "codex-rs/code-mode-protocol/src/session.rs",
    "kind": "type_item",
    "sha256": "2b260f7e5296ecd29f1913adfc1babd6c7fb53044eaf0699bb68d68350ffa5e0",
    "fileSha256": "fe20ee15f845c03576a7d77cc88fc4f8c912403e8e42c2c2fe91ade01ffd5aac",
    "executableMembers": []
  },
  {
    "id": "codex-rs/code-mode/src/grpc_session/transport.rs#type_item@581",
    "file": "codex-rs/code-mode/src/grpc_session/transport.rs",
    "kind": "type_item",
    "sha256": "5c5ad617a944e6644d5b91667a2571cd93d82764e28e326667441967f55a3c01",
    "fileSha256": "5720ce2f30b38e418a61a5150404830b7186d2c6aa83ea0511788cf93f8536ac",
    "executableMembers": []
  },
  {
    "id": "codex-rs/core/src/tools/code_mode/response_adapter.rs#function_signature_item@280",
    "file": "codex-rs/core/src/tools/code_mode/response_adapter.rs",
    "kind": "function_signature_item",
    "sha256": "c44b6366edcc4bc9536cee41a8abd5bfc464ecea2e09d3838bf3b482a9827320",
    "fileSha256": "702b85e1a50baf24470f5018c355f27d7630134a2a9ea7b9a938c292702568f7",
    "executableMembers": []
  },
  {
    "id": "codex-rs/core/src/tools/code_mode/response_adapter.rs#trait_item@252",
    "file": "codex-rs/core/src/tools/code_mode/response_adapter.rs",
    "kind": "trait_item",
    "sha256": "37432b4c056acb07141cd23aeff03bf0520312fe1c7a1ae4efb23c075848ae80",
    "fileSha256": "702b85e1a50baf24470f5018c355f27d7630134a2a9ea7b9a938c292702568f7",
    "executableMembers": []
  },
  {
    "id": "codex-rs/core/src/tools/context.rs#type_item@1721",
    "file": "codex-rs/core/src/tools/context.rs",
    "kind": "type_item",
    "sha256": "af1ccf2e96907411b69e5cd5db7b10f41bf9a39e48ad2a4bbd201f44c7da1008",
    "fileSha256": "f7c4945eebc54bedb8a53723cf9efe75331f67736ceab95c8b42ada11d9e2f73",
    "executableMembers": []
  },
  {
    "id": "codex-rs/core/src/tools/registry.rs#trait_item@2503",
    "file": "codex-rs/core/src/tools/registry.rs",
    "kind": "trait_item",
    "sha256": "cd27937813214f299f5fdb97234eaff2056c303e934abb1db6c3a028ac588304",
    "fileSha256": "fa928f78a1c2b06778d97138c195f46c574072e4538a9cb99bdec33ecd171a15",
    "executableMembers": [
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@2668",
        "sha256": "bfb83eda4d24907b70e483841cba119c835f63b743ca77a8bb7089046e7f19b3"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@2817",
        "sha256": "9114efda4ef660b3d5227114e5c54c19ad3de8ccf92b1116e04666eb999b4b83"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@2970",
        "sha256": "795b3722d0253e6a894b3c44c448ca27f323f439d7cb6c7f8431bc71290d502a"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@3305",
        "sha256": "9a33cdb3e9c8d8fc23c1eb4cae05147bd35165f970214413ca93172f46a4686c"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@3615",
        "sha256": "5c09dcc2913bd7fa10e916f57bd2388c9d54a4c80f5e1fc3813bf9b6366c4988"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@3866",
        "sha256": "35e8f04aeda14d53e7bd92246e9d1ed52362fce898b05569a2236415c84599c8"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@3997",
        "sha256": "786a324d67348b5c0cc5a2be5fd8627e04df15992dc41317313709fa7facf660"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@4259",
        "sha256": "ed78df28494d23cb158e9e052aea799d67f7b19304f2c3b742079b72c5220282"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@4425",
        "sha256": "a112f0db01fbbbacad3d04c099a18081f9a1ea50729507e8e25272d7ef8527e4"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@4493",
        "sha256": "2f8cd127601867e0856f6384e59570a9a6539652521f1d87dc380eea0eca640d"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@4683",
        "sha256": "509c400d9c96e812ef9b8182c1d4fa25c20e5578c1d924cbad304ea022bd8652"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@4866",
        "sha256": "8411f70d7f95ea2c15e8664f4b06cac5b6cecdf08ed8d6c46e8d8c967b7f029c"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@4964",
        "sha256": "9772eaded3c88da0724180de4405bb79df50d300fa49109accc529e5104b9a20"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@6371",
        "sha256": "c340512c7b95ad4ca45934fd6587c7e4b2b6877620612ef0ca4e22a550a6bb21"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@6969",
        "sha256": "ec889f3d5dcb50849f866caeaa29e94f5f074b1289873cde2da1daecc8da5004"
      },
      {
        "id": "codex-rs/core/src/tools/registry.rs#function_item@7276",
        "sha256": "d1fe252e74e77f6e8f80848c54f2c28573aad76becbe10646a71aa6f5d988a5c"
      }
    ]
  },
  {
    "id": "codex-rs/ext/extension-api/src/contributors.rs#type_item@2713",
    "file": "codex-rs/ext/extension-api/src/contributors.rs",
    "kind": "type_item",
    "sha256": "bef4a371b8e00a35e13e22b10a34e98b08dc8228601180c2d2a5759b7145bc8f",
    "fileSha256": "7c4d2d96fa9a04006ea5555e4fb731d0694a81a1b35e513d61edd7169328b8cd",
    "executableMembers": []
  },
  {
    "id": "codex-rs/rollout-trace/src/model/mod.rs#type_item@1052",
    "file": "codex-rs/rollout-trace/src/model/mod.rs",
    "kind": "type_item",
    "sha256": "40d1a7c7b2546e5434aff31d902d4b76ad356a418ab885bf6593fb935adc7222",
    "fileSha256": "5efe565bc430a194226d7f162fd8acb2321a57b1834ff4733783f4fbb239099a",
    "executableMembers": []
  },
  {
    "id": "codex-rs/rollout-trace/src/model/mod.rs#type_item@1159",
    "file": "codex-rs/rollout-trace/src/model/mod.rs",
    "kind": "type_item",
    "sha256": "295906433d5e5d017affd36258abf0d8e25f045838f6574437364b3bb4f6dcd8",
    "fileSha256": "5efe565bc430a194226d7f162fd8acb2321a57b1834ff4733783f4fbb239099a",
    "executableMembers": []
  },
  {
    "id": "codex-rs/rollout-trace/src/model/mod.rs#type_item@1270",
    "file": "codex-rs/rollout-trace/src/model/mod.rs",
    "kind": "type_item",
    "sha256": "70768415b602aa95398d5a1eb03cd60f1bc5e27cf3c9be9b182c1212f7107dcd",
    "fileSha256": "5efe565bc430a194226d7f162fd8acb2321a57b1834ff4733783f4fbb239099a",
    "executableMembers": []
  },
  {
    "id": "codex-rs/rollout-trace/src/model/mod.rs#type_item@480",
    "file": "codex-rs/rollout-trace/src/model/mod.rs",
    "kind": "type_item",
    "sha256": "7dd2c82e71096e9fac4eff030f4006789713c5be8121d01a6ac581956fff2649",
    "fileSha256": "5efe565bc430a194226d7f162fd8acb2321a57b1834ff4733783f4fbb239099a",
    "executableMembers": []
  },
  {
    "id": "codex-rs/rollout-trace/src/model/mod.rs#type_item@683",
    "file": "codex-rs/rollout-trace/src/model/mod.rs",
    "kind": "type_item",
    "sha256": "7a39057bee9ef05c4033abb8d6a3558aff45dec57748b9aa7fc6a5b9a4fe6e5a",
    "fileSha256": "5efe565bc430a194226d7f162fd8acb2321a57b1834ff4733783f4fbb239099a",
    "executableMembers": []
  },
  {
    "id": "codex-rs/tools/src/tool_executor.rs#function_signature_item@4006",
    "file": "codex-rs/tools/src/tool_executor.rs",
    "kind": "function_signature_item",
    "sha256": "50fd0e6014556edf3ae9f41cbcb2cddd6cec54b9949ded804e9fbe586b456c8d",
    "fileSha256": "06ddd20a3cfb34887e3bf0b657d386e403681e1583675e913da9779852ff2741",
    "executableMembers": []
  },
  {
    "id": "codex-rs/tools/src/tool_executor.rs#function_signature_item@4044",
    "file": "codex-rs/tools/src/tool_executor.rs",
    "kind": "function_signature_item",
    "sha256": "8bcacca9c80c8459a675467a8f3ecb1893eb221009a96a456ccd34875433a703",
    "fileSha256": "06ddd20a3cfb34887e3bf0b657d386e403681e1583675e913da9779852ff2741",
    "executableMembers": []
  },
  {
    "id": "codex-rs/tools/src/tool_executor.rs#function_signature_item@4926",
    "file": "codex-rs/tools/src/tool_executor.rs",
    "kind": "function_signature_item",
    "sha256": "2ba27a3e7f1c78fb05585612c957e5f734a1d64a8b2925bbd4c283a9ef5b518d",
    "fileSha256": "06ddd20a3cfb34887e3bf0b657d386e403681e1583675e913da9779852ff2741",
    "executableMembers": []
  },
  {
    "id": "codex-rs/tools/src/tool_executor.rs#type_item@281",
    "file": "codex-rs/tools/src/tool_executor.rs",
    "kind": "type_item",
    "sha256": "8209fb4fa7677e2a5950e85f86ea5eefdb8df287941c680b5ded5f56f6b37828",
    "fileSha256": "06ddd20a3cfb34887e3bf0b657d386e403681e1583675e913da9779852ff2741",
    "executableMembers": []
  },
  {
    "id": "codex-rs/tools/src/tool_output.rs#function_signature_item@1587",
    "file": "codex-rs/tools/src/tool_output.rs",
    "kind": "function_signature_item",
    "sha256": "f7cdca94b628a3d98c8b73e91d60fbb4fd0422d9f0100ca12434e1217b0a6b09",
    "fileSha256": "e07566b50ec4492ffafd6fa0b4704e22b3c32745213c6089f19b47baf3e873d3",
    "executableMembers": []
  },
  {
    "id": "codex-rs/tools/src/tool_output.rs#function_signature_item@768",
    "file": "codex-rs/tools/src/tool_output.rs",
    "kind": "function_signature_item",
    "sha256": "3d26080851c9380f3a740a13ac6e802654c3f080129d5214405693ef04b666f1",
    "fileSha256": "e07566b50ec4492ffafd6fa0b4704e22b3c32745213c6089f19b47baf3e873d3",
    "executableMembers": []
  },
  {
    "id": "codex-rs/tools/src/tool_output.rs#function_signature_item@805",
    "file": "codex-rs/tools/src/tool_output.rs",
    "kind": "function_signature_item",
    "sha256": "50242a5505278f86f777643f7b01a8d36cc764bc1efa208fbc9d19e2d33cb9a9",
    "fileSha256": "e07566b50ec4492ffafd6fa0b4704e22b3c32745213c6089f19b47baf3e873d3",
    "executableMembers": []
  },
  {
    "id": "codex-rs/tools/src/tool_output.rs#trait_item@390",
    "file": "codex-rs/tools/src/tool_output.rs",
    "kind": "trait_item",
    "sha256": "a8447f215f97882e16c69282e615f5e8619586acbaab8b670e58efd68b554316",
    "fileSha256": "e07566b50ec4492ffafd6fa0b4704e22b3c32745213c6089f19b47baf3e873d3",
    "executableMembers": [
      {
        "id": "codex-rs/tools/src/tool_output.rs#function_item@1043",
        "sha256": "be917ee313006ffb5e1748d27366dd66ab24f831d60bf188c3a90e91ee91cfb1"
      },
      {
        "id": "codex-rs/tools/src/tool_output.rs#function_item@1272",
        "sha256": "e8f5407600014e20c78c8e89ab2b836650a5b6aec6a49825a1b4c0d7d1c940ea"
      },
      {
        "id": "codex-rs/tools/src/tool_output.rs#function_item@1504",
        "sha256": "8fc626536bae4c85aca85d6a3b9734cd780ba0f0317505f91a2b37a0153bdd73"
      },
      {
        "id": "codex-rs/tools/src/tool_output.rs#function_item@1760",
        "sha256": "5e5316d40149baaae2a31f69d9b328780c8202759e9c49ad010b8215ef12db70"
      },
      {
        "id": "codex-rs/tools/src/tool_output.rs#function_item@1932",
        "sha256": "64c9434e8d68abbc0abd39d3e14986cc27e7a31a4cd6ddf5b9eacd712ea30018"
      },
      {
        "id": "codex-rs/tools/src/tool_output.rs#function_item@2467",
        "sha256": "be7dc98ab52367a82bbc4afbfdd1d000885e4bb3c9a3f6d8c3f882060bb6180d"
      },
      {
        "id": "codex-rs/tools/src/tool_output.rs#function_item@2587",
        "sha256": "64f87ff58760bc8fffe80e2408c3644bd0f2599ebd964a2def27c1694ee9e43e"
      },
      {
        "id": "codex-rs/tools/src/tool_output.rs#function_item@2834",
        "sha256": "bcaf7a230514771f838c16d684ca8c0695d26d667b2f02ebcd0a78819c7459fb"
      }
    ]
  }
];

/** Source declarations do not execute. Default bodies and concrete implementations
 * retain their own source-unit obligations; this predicate cannot cover them. */
export function declarationContextReason(unit: SourceUnit, inventory: CurrentInventory): string | undefined {
  if (inventory.sourceRevision !== reviewedRevision || !["trait_item", "type_item", "function_signature_item"].includes(unit.kind)) return undefined;
  const proof = reviewed.find(entry => entry.id === unit.id && entry.file === unit.file && entry.kind === unit.kind && entry.sha256 === unit.sha256);
  if (!proof || !inventory.files.some(file => file.path === proof.file && file.sha256 === proof.fileSha256)) return undefined;
  if (!inventory.units.some(entry => entry.id === unit.id && entry.kind === unit.kind && entry.sha256 === unit.sha256)) return undefined;
  const bodies = inventory.units.filter(member => member.file === unit.file && member.kind === "function_item" && unit.start <= member.start && member.end <= unit.end);
  if (bodies.length !== proof.executableMembers.length || proof.executableMembers.some(body => !bodies.some(member => member.id === body.id && member.sha256 === body.sha256))) return undefined;
  const form = unit.kind === "trait_item" ? "Rust trait declaration/accounting container" : unit.kind === "function_signature_item" ? "bodyless Rust trait method signature" : "transparent Rust type alias";
  return `Reviewed exact nonwire ${form}: ${unit.id}. Its source bytes and complete defining-file hash are retained. This declaration supplies no executable body or separate serialized value and receives no execution PASS. Every concrete implementation and every default function body remains a separately inventoried obligation; executable members retained under this declaration: ${proof.executableMembers.map(body => body.id + "@" + body.sha256).join(", ") || "none"}.`;
}
