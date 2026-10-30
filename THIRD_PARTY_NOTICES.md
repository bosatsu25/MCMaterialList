# Third-party notices

MCMaterialList itself is licensed under the MIT License (see `LICENSE`). That license covers only this project's own code and resources. It does not relicense any dependency listed below. Each dependency keeps the license its own authors declare.

This file is informational and is not legal advice. Nothing here has been reviewed by a lawyer.

## Contents of the distributed JAR

The application JAR built by `:fabric:jar` contains only this project's own work:

| Component | Origin | License |
| --- | --- | --- |
| `dev.mcmateriallist.fabric` classes, mixin configuration, `fabric.mod.json` | this project | MIT |
| `assets/mcmateriallist/lang/en_us.json`, `ja_jp.json` | this project | MIT |
| Nested `META-INF/jars/core-<version>.jar` (`dev.mcmateriallist.core`) | this project | MIT |
| `LICENSE`, `THIRD_PARTY_NOTICES.md` | this project | MIT |

No third-party code, library, image or other asset is bundled. `verifyApplicationJar` fails the build if the JAR contains any nested JAR other than `core`, any Gson, Litematica or MaLiLib class, or test-only code. It also fails if the JAR's `fabric.mod.json` does not declare `MIT`, or if `LICENSE` or this file is missing.

## Runtime dependencies (not distributed)

The player or server operator installs these separately. The license values below are the `license` field each pinned artifact declares in its own `fabric.mod.json`. `verifyDependencyLicenses` reads the exact pinned JARs during the build and fails if any value, mod ID or version differs from this table.

| Mod ID | Version | License | Side | Name |
| --- | --- | --- | --- | --- |
| `fabricloader` | 0.19.3 | Apache-2.0 | client and server | Fabric Loader |
| `fabric-api` | 0.154.0+26.2 | Apache-2.0 | client and server | Fabric API |
| `litematica` | 0.28.3 | LGPLv3 | client only, optional | Litematica |
| `malilib` | 0.29.2 | LGPLv3 | client only, optional | MaLiLib |

Other runtime requirements:
- **Minecraft 26.2** is proprietary software from Mojang Studios, used under the [Minecraft EULA](https://aka.ms/MinecraftEULA). It is not distributed with this project. Running the dedicated-server tests requires accepting that EULA (`-PacceptMinecraftEula=true`).
- **Java 25** is provided by the user's runtime.
- **Gson**: the `core` module compiles against `com.google.code.gson:gson:2.14.0`, whose Maven POM declares Apache-2.0. At runtime it uses the Gson that Minecraft itself ships. Gson is not bundled in the JAR.

### Litematica and MaLiLib

The JAR does not include or copy any Litematica or MaLiLib code. On the client, MCMaterialList calls their public classes, and when they are present it applies Mixins to some of their screens at runtime. Its `fabric.mod.json` lists them under `suggests`, not `depends`. The dedicated server never loads them; `verifyServerSmoke` and `verifyMultiplayer` check this.

Whether runtime Mixins into an LGPLv3 library create any obligation beyond keeping that library separately replaceable has not been legally assessed. Users and redistributors who need certainty should get their own advice.

## Build and test tools (not distributed)

Gradle, Fabric Loom, JUnit, the Error Prone annotations (compile-only) and the Fabric gametest API are used only to build and test. None of them is included in the application JAR.
