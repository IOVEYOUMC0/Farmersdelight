# UltimateAdvancementAPI dependency

`UltimateAdvancementAPI-Plugin-2.8.1-pro.2.jar` is used only for compilation. Farmersdelight-Plugin-Pro does not shade it into its plugin or API JAR. To enable advancements, install this JAR separately in the server's `plugins/` directory, replacing any previous UltimateAdvancementAPI JAR.

## Compatibility

- Modern distribution: Paper 26.2/26.3 and Folia scheduling; Java 25 required.
- Verified integration: Paper 26.3 build 140 and Folia 26.2 build 7, with CraftEngine 26.10-SNAPSHOT and all 23 Farmersdelight advancement nodes.
- This JAR includes only the 26.2/26.3 native adapters. Servers on older Minecraft versions need an API distribution matching their version.
- No official Folia 26.3 build was available on 2026-10-01. Its native protocol adapter is verified on Paper 26.3; Folia 26.3 verification is pending an official server build.
- The existing runtime loader downloads CommandAPI 12.1.0 and the configured database driver. Compilation uses the local dependency without downloading the API JAR.

Farmersdelight uses ordinary incremental advancement updates during gameplay, and `forceUpdateAdvancements(player)` after resource-pack reloads to restore the client's definitions. Namespace rebuilds retain enough client history to remove obsolete nodes.

Automatic layout uses iterative vanilla-style subtree spacing. Paper datapack reload completion resets client snapshots and resends definitions; normal updates remain incremental. Command mutations run on the target player's scheduler, with a native command fallback when CommandAPI is unavailable.

Toast titles and descriptions retain their translation components. Announcement sentences use vanilla client translation keys, so the message and title follow each receiving player's language.

## Provenance and sources

The optimized API is based on [Nodemc-developing/UltimateAdvancementAPI](https://github.com/Nodemc-developing/UltimateAdvancementAPI), revision `67d9576ae5e4ec55701ac653194bb77c5f1e708c`. It retains the original API package names and the credits of fren_gor, EscanorTargaryen and the upstream contributors. This modified distribution adds ydxc2009 to the plugin's authors.

The matching optimized source is published at [revision 8ffb53d](https://github.com/Nodemc-developing/UltimateAdvancementAPI/tree/8ffb53d175bc03c585341e30d0ee9373c6f58d8c). The complete source of this build is also provided in the archive below. Iterative tidy-tree layout and the native command fallback adapt changes from [IOVEYOUMC0/UltimateAdvancementAPI, revision 14b8895](https://github.com/IOVEYOUMC0/UltimateAdvancementAPI/tree/14b88955db9e27b2ff7aa829fe4f0763a393212a), with original license notices retained.

Corresponding source, build scripts, Gradle wrapper and original license notices are included in `UltimateAdvancementAPI-2.8.1-pro.2-sources.zip`. Extract it and follow its `MODERN_BUILD.md`; `./gradlew build` produces the plugin and source archive. The changes include ordered and batched persistence, team-cache synchronization, owner-thread scheduling, incremental packets and the 26.3 adapter.

UltimateAdvancementAPI retains **LGPL-3.0-or-later** licensing. The original license texts are copied as `UltimateAdvancementAPI-LGPL-3.0.txt` and `UltimateAdvancementAPI-GPL-3.0.txt`, and are also present in the JAR and source archive. Farmersdelight's own licensing is described in the repository's `LICENSE` and `NOTICE.md`.

## SHA-256

| Artifact | SHA-256 |
| --- | --- |
| `UltimateAdvancementAPI-Plugin-2.8.1-pro.2.jar` | `e2a247b4c5e77f7489bad3e9bce93e9b8e0a91eebd7edbb36e803e0257af5c6a` |
| `UltimateAdvancementAPI-2.8.1-pro.2-sources.zip` | `bc3c714eecc710fa3fc6eeff22f15e8351695f46661b7bdce4aeaab46ea7e851` |
