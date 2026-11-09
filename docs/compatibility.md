# Compatibility and upstream boundaries

Issue #14 (Phase 5C). This page lists what MCMaterialList is tested with, every place it hooks into an upstream mod, which gate exercises each hook, and the upstream behaviour it depends on. It is an audit of the code at the time of writing; it is not legal or support advice.

## Tested versions

Only this exact combination is tested. Nothing else is claimed.

| Component | Tested | Declared in `fabric.mod.json` | Runtime check | Where the version is pinned |
| --- | --- | --- | --- | --- |
| Minecraft | 26.2 | `depends: "=26.2"` | Fabric Loader refuses to start otherwise | `gradle.properties` `minecraft_version` |
| Fabric Loader | 0.19.3 | `depends: "=0.19.3"` | Fabric Loader | `loader_version` |
| Fabric API | 0.154.0+26.2 | `depends: "=0.154.0+26.2"` | Fabric Loader | `fabric_api_version` |
| Litematica | 0.28.3 (Modrinth `Yxyi6tlv`) | `suggests: "=0.28.3"` (client only, optional) | `Compatibility.isSupported()` exact version string | `litematica_version`; `verifyDependencyLicenses` reads the artifact's own `fabric.mod.json` |
| MaLiLib | 0.29.2 (Modrinth `52cWF5Da`) | `suggests: "=0.29.2"` (client only, optional) | `Compatibility.isSupported()` exact version string | `malilib_version`; same check |
| Java | 25 (hosted CI: Microsoft OpenJDK 25 via `actions/setup-java`; Phase 0: Microsoft OpenJDK 25.0.4+7) | `depends: "java": ">=25"` | Fabric Loader | Gradle toolchain and `options.release = 25` |
| Build only | Gradle 9.7.1, Loom 1.17.20 | - | - | wrapper, `fabric/build.gradle` |
| Platforms with evidence | Hosted Ubuntu 24.04 with `xvfb-run` (every gate, `verifyRelease`); Windows for the Phase 0-2 local runs; macOS not verified ([platform-verification.md](platform-verification.md)) | - | - | `.github/workflows/phase-0.yml` |

Notes:

- `java: ">=25"` is the loader floor, not a claim: only Java 25 is tested. A newer Java may start the game but is untested.
- With any other Litematica or MaLiLib version, or without them, the client adapter turns itself off (see [Version gates](#version-gates)). The common entrypoint, the shared server and Check Materials' server side still run.
- The dedicated server needs neither Litematica nor MaLiLib (see [Isolation](#isolation)).
- `Compatibility.java` and the `suggests` block repeat the Litematica/MaLiLib versions by hand. A version bump must change `gradle.properties`, `Compatibility.java`, both `fabric.mod.json` entries, the Modrinth IDs in `fabric/build.gradle`, `THIRD_PARTY_NOTICES.md` and this table together. `verifyDependencyLicenses` catches a mismatch between the first and the artifacts, not the others.

## Stored data and wire formats

A newer build reads the formats below; an older build that meets a newer schema reports `UNSUPPORTED_SCHEMA`, never rewrites the file and blocks the writes that depend on it.

| Data | Location | Schema written by this build | Also reads | Older builds |
| --- | --- | --- | --- | --- |
| Local material/region work | `config/mcmateriallist/placements/<placement UUID>/materials.json`, `regions.json` | 1 (`DatasetJsonCodec`) | - | Same schema |
| **Client shared state** | `config/mcmateriallist/shared-client/` | **3** (`SharedClientStore`, since PR #32 / Issue #11: detached region copy) | 1, 2 (rewritten as 3 on the next save) | **Builds before PR #32 read the file as `UNSUPPORTED_SCHEMA`**: "Sharing settings require a newer version. Shared writes are blocked." Builds before PR #24 have no shared state and ignore the folder. |
| Location Files | `config/mcmateriallist/location-files/` | 1 | - | Builds before PR #29 have none |
| Shared projects (server) | `<world>/mcmateriallist-shared/` | project 2, world 1 (`SharedProjectStore`) | project 1 | Builds before PR #25 cannot read project schema 2 |
| Check Materials grants (server) | `<world>/mcmateriallist-stock/grants/` | 1 | - | Builds before PR #31 have none |
| Shared wire protocol | custom payload | version 2 (`SharedProtocol.VERSION`) | negotiates a version-1 `Hello` only to answer UNSUPPORTED | A version-1 peer keeps local work but cannot share |
| Check Materials wire protocol | custom payload | version 1 (`StockProtocol.VERSION`) | - | Without it the client falls back to observed counts |

### Upgrade and downgrade notes

- **Upgrade** keeps all data: schema 1 and 2 client shared state is read and rewritten as schema 3 on the next save; local work and Location Files are unchanged.
- **Downgrade below PR #32 is not supported once this build has saved shared state.** The older build blocks shared writes for that placement (schema 3 is `UNSUPPORTED_SCHEMA`), and it cannot see a detached region copy. Builds from PR #24 to PR #31 do not block editing the *original* local regions while sharing metadata is unreadable, so edits made there stay in `regions.json` and are hidden again behind the detached copy after re-upgrading. Keep a copy of `config/mcmateriallist/` before downgrading.
- Client and server should run the same build. Shared and Check Materials messages carry their own version; a mismatch is refused, not reinterpreted.

## Version gates

| Gate | Owner (source) | What it does | Regression evidence |
| --- | --- | --- | --- |
| Exact `depends` (Minecraft, Loader, Fabric API) | `fabric/src/main/resources/fabric.mod.json` | Loader refuses other versions | Every run task boots with the pinned versions; `verifyApplicationJar` checks the shipped metadata (id, version, license) |
| `Compatibility.isSupported()` | `fabric/src/client/.../Compatibility.java` (Phase 0) | Compares Litematica and MaLiLib friendly version strings from loader metadata; loads no class of either mod | Positive path: every client gametest (the adapter logs "client adapter enabled") |
| Mixin plugin | `Phase0MixinPlugin.shouldApplyMixin` (Phase 0) | Applies none of the 18 client mixins unless `isSupported()`; the config is `required: false` | Same positive path; the dedicated server never loads the client config (`verifyServerSmoke`, `verifyMultiplayer`) |
| Client services | `MCMaterialListClient.onInitializeClient` | Creates local work, Location File and shared controllers only when supported; otherwise logs "adapter disabled" | Positive path only |
| Capture refusal | `PlacementWorkAdapter.capture*` | Returns `UNSUPPORTED` when not supported | Positive path only |
| Pinned artifacts | `verifyDependencyLicenses` | The resolved Litematica/MaLiLib/Loader/Fabric API JARs must declare the pinned id and version | `verifyApplicationJar`, hence `verifySharedTransport` and `verifyPhase3`/`verifyPhase4` |

**Not covered by an automated gate:** a *client* started without Litematica/MaLiLib, or with another version of them. The disabled path is verified by source inspection only: `Compatibility` reads metadata, `SharedClientNetworking` and `StockClient`, which start unconditionally, reference no Litematica/MaLiLib class, and the mixin plugin skips every client mixin. Adding such a client run would need a third client launch configuration without the optional mods.

## Production hooks (client)

All 18 are in `fabric/src/client/java/dev/mcmateriallist/fabric/client/mixin`, configured by `mcmateriallist.client.mixins.json` (`required: false`, `defaultRequire: 0`, plugin `Phase0MixinPlugin`), and use `remap = false` (Minecraft 26.2 is unobfuscated; there are no mappings). "Require" is the injector's own `require`. Hooks with `require = 0` would silently not apply if their target vanished; their effect is asserted by the listed tests instead.

| Hook | Target (Litematica 0.28.3 / MaLiLib 0.29.2) | Purpose | Owner | Require | Regression evidence |
| --- | --- | --- | --- | --- | --- |
| `MaterialListGuiMixin` | `GuiMaterialList`: `initGui` HEAD and TAIL, `@ModifyVariable` on the `isNarrow` local, `getBrowserHeight` RETURN, `onTaskCompleted` TAIL, `drawContents` override | Material toolbar, list position, exports in the footer, footer line, refresh after an upstream recount | Phase 0, #2, #4, #10 | 1 (HEAD, ModifyVariable, onTaskCompleted); 0 (TAIL, getBrowserHeight) | `Phase0ClientTest.verifyMaterialUi`, `Phase2ClientTest` (toolbar/footer/list geometry via `UiParityEvidence.controls`), `SharedUiClientTest` recount; gate `verifyClientRestart`, `runSharedUiTest` |
| `MaterialListHeightMixin` | `WidgetListMaterialList.getAllEntries` RETURN; shadows `gui` | Hide Done filter | #2 | 1 | `Phase2ClientTest` Hide Done (15 of 19 rows, denominator unchanged) |
| `MaterialListWidgetAccess` | accessor `WidgetListMaterialList.gui` | Row to screen session | #2 | - | Every material row test in `Phase2ClientTest` |
| `MaterialRowHeightMixin` | `WidgetListBase.getBrowserEntryHeightFor(Object)` HEAD (erased generic) | 40 px rows with Show Info, material and region lists | #2, #3 | 1 | `Phase2ClientTest` Show Info rows; `Phase2RegionClientTest` "Expanded row height differs" |
| `MaterialRowMixin` | `WidgetMaterialListEntry`: `<init>` TAIL, `getColumnPosX` HEAD, `render` `@ModifyArg` on `drawString` ordinal 4 (the name), `render` HEAD/TAIL, `postRenderHovered` HEAD; shadows `maxCountLength1-3`, `listWidget` | Head/Done/notice buttons, column layout, name clamp, Show Info and stock line, tooltips | #2, #10 | 1 | `Phase2ClientTest` row clicks, quantity header sort hits, tooltips; `Phase4StockClientTest` stock segment |
| `MaterialListPlacementAccess` | accessor `MaterialListPlacement.placement` | Owner placement of a material list (`WRONG_LIST`) | Phase 1 | - | `Phase1ClientTest.verifyReadiness`; every material capture |
| `MaterialListReadinessMixin` | `MaterialListBase.setMaterialListEntries` TAIL | Readiness flag and shared count provenance | Phase 1, #5 | 0 | `Phase1ClientTest.verifyReadiness` (not ready, busy, cancelled), every Track materials; `SharedUiClientTest` count barrier |
| `PlacementMaterialRefreshMixin` | `MaterialListPlacement.reCreateMaterialList` HEAD | Invalidate readiness, start count provenance | Phase 1, #5 | 0 | `Phase1ClientTest.verifyReadiness`; `SharedUiClientTest` "full_recount_required" |
| `PlacementConfigurationGuiMixin` | `GuiPlacementConfiguration`: `getBrowserHeight` RETURN, `initGui` HEAD/TAIL, `drawContents`; shadows `placement` | Region toolbar, list position, footer | Phase 0, #3, #4 | 1 | `Phase0ClientTest.verifyPlacementUi`, `Phase2RegionClientTest` (list y = 62, geometry), `UpstreamBoundaryEvidence` |
| `RegionListMixin` | `WidgetListPlacementSubRegions.getAllEntries` RETURN; shadows `parent` | Hide Done filter | #3 | 1 | `Phase2RegionClientTest` Hide Done (57 of 62) |
| `RegionRowMixin` | `WidgetPlacementSubRegion`: `<init>` TAIL, three `render` `@ModifyArg`, `@Redirect` of `isRegionPlacementModifiedFromDefault`, `render` HEAD/TAIL, `canSelectAt` HEAD, `postRenderHovered` HEAD; shadows `parent`, `placement`, `buttonsStartX` | Region Head/Done/notice, combined native notice, name clamp, selection hits | #3, #4 | 1 | `Phase2RegionClientTest` row clicks, native notice bubble, scaled/narrow Japanese names |
| `GuiButtonsAccess` | accessor `GuiBase.buttons` | Compact footer positioning | #4 | - | `Phase2ClientTest`/`Phase2RegionClientTest` narrow geometry |
| `ListSelectionAccess` | accessors `WidgetListBase.lastSelectedEntry`, `lastSelectedEntryIndex` | Keep selection across list recreation (`WorkListViewState`) | #4 | - | `UiParityEvidence.recreation` in both Phase 2 tests |
| `SearchBoxAccess` | accessor `WidgetSearchBar.searchBox` | Keep search text across recreation | #4 | - | Same |
| `SharedLoadedSourceMixin` | `LitematicaSchematic.readFromFile(FileType)` HEAD/RETURN; `@Redirect` in `readNbtFromFile` of `NbtUtils.readNbtFromFile(Path)` | Proof of which file bytes a loaded schematic came from | #5 | 1 | `SharedUiSourceChecks`, `SharedUiClientTest`; `FixtureWorlds.drainSources` in every gametest |
| `SharedNbtStreamMixin` | `NbtUtils.readNbtFromFile(Path, NbtAccounter)`: `@Redirect` of `Files.newInputStream` | Digest the bytes actually read | #5 | 2 | Same |
| `SharedPlacementRevisionMixin` | `SchematicPlacement.onModified` (both overloads) HEAD | Placement revision for shared captures | #5 | 1 | `SharedUiClientTest` placement move / sub-region toggle |
| `SharedSchematicRevisionMixin` | `SchematicMetadata.setModifiedSinceSaved` HEAD | Unsaved schematic edit revision | #5 | 1 | `SharedUiSourceChecks` "Native same-geometry unsaved edit" (`SharedUiSchematicEdit`) |

Every target method, field and descriptor above was checked against the pinned tags `sakura-ryoko/litematica@26.2-0.28.3` and `sakura-ryoko/malilib@26.2-0.29.2` during this audit.

## Test-only hooks

These ship in test source sets only; `verifyApplicationJar` fails if any test class or test mixin config is in the application JAR.

| Config (source set) | Hooks | Targets | Gate |
| --- | --- | --- | --- |
| `mcmateriallist.test.mixins.json` (`gametest`, `required: true`, `defaultRequire: 1`) | 18 accessors/invokers (`*TestAccess`) | MaLiLib `GuiBase`, `GuiListBase`, `WidgetListBase`, `WidgetContainer`, `ButtonBase`, `ButtonGeneric`, `Message`, `MessageRenderer`, `InfoUtils`; Litematica `TaskScheduler`, `TaskProcessChunkBase`, `WidgetMaterialListEntry`, `LitematicaSchematic`; own screens and sessions | `verifyClientRestart` |
| `mcmateriallist.transport-test.mixins.json` (`transportTest`) | 11 observers/controls | Own `SharedServerNetworking` internals, `SharedClientNetworking`, `BoundedOutgoing`, `SharedPayload` codec | `verifySharedTransport` |
| `mcmateriallist.shared-ui-test.mixins.json` (`sharedUiTest`) | `SharedUiAccess` accessors, delays, barriers, probes | Own client/core services; MaLiLib `GuiBase`, `ButtonBase`, lists, `MessageRenderer`, `InfoUtils`; Litematica `MaterialListBase`, `SchematicUtils` | `runSharedUiTest` |
| `mcmateriallist.multiplayer-test.mixins.json` (`multiplayerTest`) | `MultiplayerResultDrop` | Own `SharedClientNetworking.decoded` | `verifyMultiplayer` |

## Isolation

Upstream-specific code is confined to the `client` source set, which is the adapter boundary: Litematica is referenced from 23 client files (`litematica/`, `material/`, `region/`, `shared/`, `work/`, `mixin/`); MaLiLib alone from the screens in `location/`, `stock/` and the rest. `core` and the common `main` source set reference neither.

| Check | What it proves |
| --- | --- |
| `verifyIsolation` (part of `check`) | `core` bytecode has no `net/minecraft/`, `net/fabricmc/` or `fi/dy/masa/` constant; `main` has no `net/minecraft/client/`, `fi/dy/masa/` or `dev/mcmateriallist/fabric/client/`; no Modrinth artifact is on the server runtime classpath |
| `verifyServerSmoke` | A dedicated server without Litematica/MaLiLib boots, reaches the isolation and shared-authority checkpoints, and its `-Xlog:class+load` log contains no `fi.dy.masa.`, `net.minecraft.client.` or `dev.mcmateriallist.fabric.client.` class |
| `verifyMultiplayer` | The same class-load check for both boots of the restarted dedicated server, with two real clients |
| `verifyApplicationJar` | Only the project's own classes and nested `core` ship; no Litematica/MaLiLib/Gson class and no test code |

## Temporary, nonpersistent and duplicate placements

Work is keyed by Litematica's persistent placement UUID (`SchematicPlacement.getHashId()`, saved as `hash_code`). `PlacementIdentity.get` refuses a placement that `shouldBeSaved()` is false for, whose schematic has no file, or whose UUID another active placement shares. Every refusal blocks writes; nothing is stored under a guessed identity. Both screens re-check the identity while open: the region screen through `PlacementWorkAdapter.captureRegions`, the material screen through `PlacementIdentity.holds` (added by this audit).

| Case | Adapter result | Material screen | Region screen | Evidence |
| --- | --- | --- | --- | --- |
| Temporary (`createTemporary` of an in-memory schematic) or `shouldBeSaved() == false` | `UNREGISTERED` until registered, then `UNSAVED_OR_DUPLICATE` | not writable; footer "Saved placement identity unavailable." | not writable; same footer | `Phase0ClientTest.verifyIdentity` (identity refused); screens: inspection |
| Not registered with the placement manager (or removed while its screen is open) | `UNREGISTERED` | not writable; same footer | not writable; "Saved placement identity unavailable." when opened so, "Saved region definitions unavailable" when removed while open | `Phase1ClientTest.verifyReadiness` (adapter); screens: inspection |
| Copy with the same UUID registered beside the original | `UNSAVED_OR_DUPLICATE` for both | open original: not writable, Done disabled, "Saved placement identity unavailable.", a real Done click changes nothing, nothing written; removing the copy restores it | copy: "Saved placement identity unavailable.", Refresh disabled, a real click writes nothing; original: "Saved region definitions unavailable", Refresh disabled; removing the copy restores it unchanged | `UpstreamBoundaryEvidence.duplicateMaterialPlacement` (in `Phase2ClientTest`) and `duplicatePlacement` (in `Phase2RegionClientTest`), both new; `Phase0ClientTest.verifyIdentity` |
| Material list not made from a placement (area or schematic list) | `UNREGISTERED` (no owner) | not writable; "Saved placement identity unavailable." | - | inspection |
| Unsaved edit of the schematic while shared | capture refused | shared commands refused, source bytes unchanged | same | `SharedUiSourceChecks` "Refused unsaved design" |
| The Phase 0 **MCMaterialList** button on such a placement | - | - | error message with the refusal reason, no UUID or path | `Phase0Ui.addPlacementButton`; inspection |

Defects fixed by this audit (writes were already refused in every case):

- The material screen showed "Work storage: INVALID_DEFINITION" for every case without an identity, blaming storage. It now shows the region screen's "Saved placement identity unavailable." (`MaterialWorkSession.feedback`).
- The material screen resolved its identity only when it was opened. A copy registered (or the placement removed) while it stayed open left Done enabled and the footer saying "Local work", while each click was silently dropped by the capture check. `MaterialWorkSession.ready()` now re-checks the identity through `PlacementIdentity.holds`, so the screen stops being writable and says why.

## Upstream behaviour MCMaterialList depends on

| Behaviour (pinned versions) | Used by | If it changed | Evidence |
| --- | --- | --- | --- |
| `SchematicPlacement` keeps a random `hashId`, writes it as `hash_code` and restores it in `fromJson` | Placement identity | Work could not be found again | `Phase0ClientTest.verifyIdentity`; restore runs of `verifyClientRestart` |
| Placement JSON without `hash_code` (older Litematica data) gets a new random UUID on every load until Litematica saves it again | Placement identity | Work saved before that save is not found (shown as not tracked); it is never attached to another placement | inspection of `SchematicPlacement.fromJson` |
| Litematica saves placements per server/world and dimension on leaving and restores them, with the same `hash_code`, on joining again | Reconnect restoration | A placement not restored must be loaded again; its work is found only if the UUID is the same | `SharedUiUnshareChecks.registered()` accepts either the restored copy or re-registers the original after a real reconnect, so the test does not depend on restoration, only on the UUID |
| `GuiPlacementConfiguration.createButton` adds TOGGLE_RENDERING twice at the same bounds (upstream bug: the case `break`s into the common `addButton`). MaLiLib dispatches a click to the first button that handles it, so the toggle runs once; both draw the same label; only the first has the hover text | Region geometry checks exclude this native-native overlap; added controls are still checked against all native controls | A fixed upstream would show one button; a double toggle would be a regression | `UpstreamBoundaryEvidence.renderingDuplicate`: exactly two native Rendering buttons at identical bounds (observed 610,22,20,20 at GUI3), one real click toggles rendering exactly once, a second click restores it, region work unchanged |
| `GuiMaterialList.onTaskCompleted` runs before `setMaterialListEntries` returns | Recount refresh is scheduled for the next client task | Refresh could read a half-published list | `MaterialListGuiMixin` comment; `SharedUiClientTest` recount |
| A queued `TaskCountBlocksPlacement` means the material list is stale | `MATERIALS_BUSY` | A stale list could be captured | `Phase1ClientTest.verifyReadiness` |
| The material name is the 5th `drawString` in `WidgetMaterialListEntry.render` | Name clamp (`@ModifyArg` ordinal 4) | Wrong text clamped | `Phase2ClientTest` geometry and screenshots |
| A `BLOCK` hit result is always within the player's interaction range | Location File registration (`LookedAtContainer`) | Out-of-reach registration | `Phase4LocationClientTest` registers the looked-at block; reach itself: inspection |
| On the client, `Level.hasChunkAt` is not trusted to report an unloaded chunk: it stayed true for 2400 ticks after a 4000-block teleport (hosted run 42686987162). The client chunk cache, asked with `ChunkSource.getChunk(x, z, false)`, is the source of truth | `LookedAtContainer.loaded` (the `not_loaded` refusal) | A look at a block in an unloaded chunk could be registered | `Phase5GCoverageClientTest` unloaded refusal, which logs both answers (`Phase 5G client chunk state after leaving`) |

## Upstream API inventory

### Litematica 0.28.3 (client only)

| Package | Classes used |
| --- | --- |
| `fi.dy.masa.litematica.data` | `DataManager` (placement manager) |
| `fi.dy.masa.litematica.gui` | `GuiMaterialList`, `GuiPlacementConfiguration`, `Icons` |
| `fi.dy.masa.litematica.gui.widgets` | `WidgetListMaterialList`, `WidgetMaterialListEntry`, `WidgetListPlacementSubRegions`, `WidgetPlacementSubRegion` |
| `fi.dy.masa.litematica.materials` | `MaterialListBase`, `MaterialListEntry`, `MaterialListPlacement` |
| `fi.dy.masa.litematica.scheduler` | `TaskScheduler`, `tasks.TaskCountBlocksPlacement` |
| `fi.dy.masa.litematica.schematic` | `LitematicaSchematic`, `SchematicMetadata`, `placement.SchematicPlacement`, `placement.SubRegionPlacement` |
| `fi.dy.masa.litematica.util` | `BlockInfoListType` |

### MaLiLib 0.29.2 (client only)

| Package | Classes used |
| --- | --- |
| `fi.dy.masa.malilib.gui` | `GuiBase`, `GuiListBase`, `GuiTextFieldGeneric`, `GuiTextFieldMultiLine`, `LeftRight`, `Message` |
| `fi.dy.masa.malilib.gui.button` | `ButtonBase`, `ButtonGeneric`, `IButtonActionListener` |
| `fi.dy.masa.malilib.gui.widgets` | `WidgetListBase`, `WidgetListEntryBase`, `WidgetListEntrySortable`, `WidgetSearchBar` |
| `fi.dy.masa.malilib.render` | `GuiContext`, `RenderUtils` |
| `fi.dy.masa.malilib.util` | `StringUtils`, `nbt.NbtUtils` |

### Minecraft 26.2 and Fabric API

| Side | Packages and classes |
| --- | --- |
| Common/server (`main`) | `net.minecraft.core` (`BlockPos`, `registries.BuiltInRegistries`), `network` (`RegistryFriendlyByteBuf`, `codec.StreamCodec`, `protocol.common.custom.CustomPacketPayload`), `resources.Identifier`, `server` (`MinecraftServer`, `level.ServerLevel`, `level.ServerPlayer`, `network.ServerGamePacketListenerImpl`, `players.NameAndId`), `world` (`Container`, `RandomizableContainer`, `item.ItemStack`, `level.block.ChestBlock`, `level.block.entity.BlockEntity`, `level.block.state.BlockState`, `level.block.state.properties.ChestType`, `level.storage.LevelResource`); Fabric `ModInitializer`, lifecycle `ServerLifecycleEvents`, `ServerTickEvents`, networking `PayloadTypeRegistry`, `ServerPlayNetworking`, `ServerPlayConnectionEvents`, `context.PacketContext` |
| Client (`client`) | `net.minecraft.client` (`Minecraft`, `input.MouseButtonEvent`, `multiplayer.ClientPacketListener`, `renderer.RenderPipelines`, `resources.DefaultPlayerSkin`), `nbt.CompoundTag`, `network.chat.Component`, `world.inventory.AbstractContainerMenu`, `world.level.block` (`Mirror`, `Rotation`), `world.phys` (`BlockHitResult`, `HitResult`) and the common classes above; Fabric `ClientModInitializer`, `ClientLifecycleEvents`, `ClientTickEvents`, `ClientPlayNetworking`, `ClientPlayConnectionEvents`, `FabricLoader` |
| Core | none (Gson only) |

Product code opens no network connection of its own: shared work and Check Materials use Minecraft custom payloads only.

### Lesson from PR #29 and how to verify an API

PR #29's first hosted run failed to compile because it called `Player.canInteractWithBlock(BlockPos, double)`, which does not exist in Minecraft 26.2. The fix relies on the game itself: a `BLOCK` hit result is only produced within interaction range (see `LookedAtContainer`).

The `fabric` module cannot be compiled where Minecraft and Fabric hosts are unreachable; hosted CI is then the only compiler. Before using an upstream API:

1. Search the repository for the exact call. An existing call in a source set that hosted CI has compiled is the strongest evidence.
2. For Litematica and MaLiLib, read the pinned tag source (`sakura-ryoko/litematica` `26.2-0.28.3`, `sakura-ryoko/malilib` `26.2-0.29.2`): signature, visibility, and for mixins the exact descriptor, injection point, ordinal and shadowed field names.
3. For Minecraft and Fabric API, without an in-repo precedent, treat the call as unverified: say so in the PR, keep it small, and do not rely on names from other Minecraft versions.
4. Let hosted CI compile it: `clean build` compiles `main` and `client` (and `core` with its tests); `verifyPhase4` compiles every test source set, because each run task depends on its classes.
5. `core` has no Minecraft dependency and is compiled and tested locally.

## CI log noise

Hosted runs pass, but their logs are not clean. The families below were recorded in the Phase 2-4 execution ledgers; this audit attributes each to the harness configuration and upstream behaviour (the hosted logs themselves were not re-read here). None comes from a product warning, and none is suppressed; compiler warnings stay enabled with `-Xlint:all`.

| Family | Cause | Disposition |
| --- | --- | --- |
| OSHI warnings | Minecraft collects hardware details through OSHI for its system report; a hosted VM under `xvfb` does not expose every sensor or firmware value | Upstream; harmless |
| Registry/data warnings | Logged by Minecraft, Fabric API, Litematica or MaLiLib while initialising in a fresh run directory. MCMaterialList registers no registry entry, only custom payload types | Upstream; not reproduced or changed here |
| Option and config diagnostics | CI starts from `clean`, the separate gates use separate run directories and the multiplayer harness deletes its directory first, so `options.txt` and the Litematica/MaLiLib configs are created with defaults; the client gametest framework also overrides some options | Expected for isolated runs |
| HTTP 401 from Minecraft services | Development launches use synthetic offline accounts (for example `McmlAlpha`) without an access token, so Minecraft's profile-key, Realms and user-properties requests are refused. Servers run with `online-mode=false` | Upstream; MCMaterialList makes no HTTP request |
| Gradle deprecations | Gradle printed only a summary, so the source was not visible. CI now passes `--warning-mode all`, which names each deprecated call in the next hosted log. In-repo candidates to check then: `project.configurations`/`project.dependencies` inside `verifyDependencyLicenses` and `project.delete` inside `verifyMultiplayer` (task actions using `Task.project`). Loom and the Fabric API test plugin are the other possible sources | Open until a hosted log with `--warning-mode all` is read |
| javac | `core` compiles with zero warnings under Gradle 9.7.1 and `-Xlint:all` (checked locally). A bare `javac` call without Gson's transitive `error_prone_annotations` shows `[classfile] InlineMe` warnings; Gradle resolves that dependency, so CI does not | No change |
