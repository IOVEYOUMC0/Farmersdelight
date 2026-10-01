# Farmersdelight-Plugin-Pro

**English** | [中文](README.zh-cn.md)

Farmersdelight-Plugin-Pro is a Paper/Folia plugin port of **Farmer's Delight**, powered by CraftEngine. It adds crops, rich soil, cooking stations, knives, food, recipe discovery, advancements and a public API for addons.

Authors: HuiDu_OwO, ydxc2009.

Version 1.0.4 opens a menu from `/fd recipe edit`: choose a station and recipe group, search or page through recipes, and return to the same list when editing is finished. It also includes optional Kaleidoscope recipe-book filling, fuzzy cooking-pot recipes and a food-group editor. See the [integration and recipe guide (Chinese)](KALEIDOSCOPE-COMPAT.zh-CN.md).

## Features

- CraftEngine items, blocks, models, resource packs and loot integration.
- Cooking pot, cutting board, stove and skillet gameplay.
- Configurable crops, farmland, ropes, mushroom colonies and storage blocks.
- Nourishment, Comfort, food effects and recipe-book integration.
- Folia-safe scheduling and a stable `com.huidu.farmersdelight.api` addon API.
- Optional addons for Brewin' And Chewin', End's Delight, Expanded Delight, Crabber's Delight, Barbeque's Delight and Villagers' Delight.

## Requirements

- Paper or Folia 1.21.4 or newer
- Java 21 for the plugin; the Minecraft 26.3 test server uses Java 25
- CraftEngine 26.8.2 or newer (compiled against 26.9.1; 26.9.2 and 26.10-SNAPSHOT also verified on Paper 26.3)

Install CraftEngine first, then place the Farmersdelight-Plugin-Pro jar in `plugins/`. Use `/fd reload` for plugin configuration and `/ce reload` after changing CraftEngine resources.

Advancements require UltimateAdvancementAPI as a separate server plugin. `libs/` includes the optimized `2.8.1-pro.2` build and corresponding sources, verified on Paper 26.3 and Folia 26.2. This artifact includes only the 26.2/26.3 adapters; older servers need an API distribution matching their version. Other features remain available without the API. See [dependency details](libs/README.md). Resource-pack reloads force a definition resend; ordinary updates synchronize only changed nodes.

## Documentation

The complete player, server, addon and API documentation is maintained in the [FarmersdelightPluginWiKi](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi) repository. It can be connected to GitBook through its GitHub integration.

## Building

```text
./gradlew build
```

The build resolves CraftEngine 26.9.1 from its official Maven repository; pass `-PceVersion=<version>` to compile against another release. CraftEngine 26.10 snapshots automatically enable native sleeping cooking-pot tickers at runtime; earlier releases use the existing scheduler. Unpublished snapshots can be compiled with `-PceJar=<plugin-jar>` and `-PceLibraries=<CraftEngine/libs-directory>` (the remapped runtime libraries). Addons compile against the generated API-only Farmersdelight-Plugin-Pro jar and must be checked out beside this repository.

## License

GNU Affero General Public License v3.0 only. See [LICENSE](LICENSE).

The plugin is fully open source: the recipe editor, recipe-to-recipe jumps and handheld skillet cooking are all in this repository. You may use, modify and redistribute it, including for a fee, as long as AGPL-3.0 is honoured: keep the copyright and licence notices, ship the complete corresponding source of the version you distribute, license your modified version under AGPL-3.0, and if you run a modified version as a network service, offer its source to the users of that service.

Third-party content (the ported Farmer's Delight assets and the bundled libraries) keeps its own notices; see [NOTICE.md](NOTICE.md) and [THIRD_PARTY.md](THIRD_PARTY.md).

This repository is the only source of the plugin. Builds published by anyone else, with or without added code, are not ours.
