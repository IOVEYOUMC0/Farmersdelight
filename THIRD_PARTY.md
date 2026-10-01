# Bundled Sparrow libraries

The plugin bundles and relocates the following libraries into its own namespace:

| Library | Version | Upstream source | License |
| --- | --- | --- | --- |
| Sparrow YAML | 1.0.22 | https://github.com/Xiao-MoMi/sparrow-yaml | GPL-3.0 |
| Sparrow UI | beta.38 | https://github.com/Catnies/sparrow-ui | Apache-2.0 |

The license texts are included under `META-INF/licenses/` in the plugin JAR. Sparrow UI's
embedded `sparrow-ui-proxy.jarinjar` is preserved; its runtime proxy installer uses the
relocated package name. Neither library is included in the API-only JAR.

Dependencies are obtained from the upstream Maven releases and snapshots repositories,
respectively. Consumers do not need to install either library as a separate server plugin.

# UltimateAdvancementAPI

UltimateAdvancementAPI `2.8.1-pro.1` is a compile-only dependency, distributed in `libs/`
with its corresponding source archive, build scripts and LGPL-3.0-or-later license texts.
It must be installed as a separate server plugin and is not bundled into either Farmersdelight JAR.
See [libs/README.md](libs/README.md) for provenance, artifact hashes and compatibility.
