# License decision (Issue #23)

## Decision

- **Project license:** MIT, a single license. It is not dual-licensed, and it is not GPL.
- **Decided by:** the repository owner, on 2026-10-30.
- **How:** in the Claude Code session that implemented this change. The owner was asked to pick exactly one of MIT, Apache-2.0, or "MIT OR Apache-2.0" and selected MIT. The owner had earlier narrowed the choice to "MIT or Apache-2.0" rather than GPL, because they did not want to require modified versions to publish their source.
- **Copyright line:** `Copyright (c) 2026 bosatsu25`.

The assistant did not choose the license. Issue #23 forbids that.

## What the decision covers

MIT applies to this repository's own code and resources:
- `core`;
- `fabric`;
- documentation;
- language files.

It does not relabel any dependency. Litematica and MaLiLib remain under the license their pinned artifacts declare (LGPLv3). They are not described as GPL or MIT anywhere in this project.

## Where it is applied

| Location | Content |
| --- | --- |
| `LICENSE` | MIT text with the copyright line |
| `fabric/src/main/resources/fabric.mod.json` | `"license": "MIT"` and a sources link |
| Application JAR | `LICENSE` and `THIRD_PARTY_NOTICES.md` at the root, plus `LICENSE` in the nested `core` JAR |
| `README.md` | License section |
| `THIRD_PARTY_NOTICES.md` | Bundled-versus-runtime dependency list, with each pinned version's declared license |

## Verification

- `verifyDependencyLicenses` resolves each pinned runtime artifact (Fabric Loader, Fabric API, Litematica, MaLiLib) without transitive dependencies. It reads that JAR's own `fabric.mod.json` and fails unless the mod ID, version and license match the row in `THIRD_PARTY_NOTICES.md`. The values are therefore read from the exact pinned artifacts, not from names or memory.
- `verifyApplicationJar` fails if the JAR's `fabric.mod.json` is not `MIT`, if `LICENSE` or `THIRD_PARTY_NOTICES.md` is missing, or if any nested JAR other than this project's own `core` is present (checked by class package). It also fails if `core` lacks the MIT `LICENSE`, or if Gson, Litematica, MaLiLib or test code is bundled.

## Limits

- This is not legal advice and was not reviewed by a lawyer.
- Whether runtime Mixins into LGPLv3 Litematica/MaLiLib screens create obligations beyond keeping those mods separately installable has not been legally assessed.
- Release readiness (#15) still depends on its other gates. This record only removes the "license undecided" blocker.
