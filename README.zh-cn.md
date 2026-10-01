# Farmersdelight-Plugin-Pro

[English](README.md) | **中文**

Farmersdelight-Plugin-Pro 是基于 CraftEngine 的 Farmer's Delight Paper/Folia 移植插件，提供作物、沃土、烹饪工作站、小刀、食物、配方发现、进度以及供附属使用的公共 API。

作者：HuiDu_OwO、ydxc2009。

1.0.4 可通过 `/fd recipe edit` 进入菜单，选择厨具和配方组、搜索或翻页，编辑完成后返回原列表；同时包含森罗菜谱自动投料兼容、厨锅模糊配方和食材分组编辑，详见[兼容与配方指南](KALEIDOSCOPE-COMPAT.zh-CN.md)。

## 项目内容

- CraftEngine 物品、方块、模型、资源包和战利品整合。
- 厨锅、砧板、炉灶和煎锅玩法。
- 可配置作物、耕地、绳索、蘑菇群落和储物方块。
- 营养、舒适、食物效果和配方书。
- Folia 安全调度，以及稳定的 `com.huidu.farmersdelight.api` 附属 API。
- 支持 Brewin' And Chewin'、End's Delight、Expanded Delight、Crabber's Delight、Barbeque's Delight 和 Villagers' Delight 等附属。

## 运行要求

- Paper 或 Folia 1.21.4 及以上
- 插件编译使用 Java 21；Minecraft 26.3 测试服使用 Java 25
- CraftEngine 26.8.2 及以上（编译基线 26.9.1；26.9.2 和 26.10-SNAPSHOT 也已在 Paper 26.3 实机验证）

先安装 CraftEngine，再将 Farmersdelight-Plugin-Pro 放入 `plugins/`。修改插件配置后使用 `/fd reload`，修改 CraftEngine 资源后使用 `/ce reload`。

成就功能需要单独安装 UltimateAdvancementAPI。`libs/` 中附有 `2.8.1-pro.2` 优化版及对应源码，支持 Paper 26.3 和 Folia 26.2；此构建只包含 26.2/26.3 适配，较旧服务端应使用对应版本的 API。没有安装 API 时，插件其余功能可继续使用。详见 [依赖说明](libs/README.md)。资源包重载后会强制重发成就定义，平时只同步变化的节点。

## 文档

完整的玩家指南、服主指南、附属指南和 API 文档已迁移到 [FarmersdelightPluginWiKi](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi)，该仓库可以通过 GitHub 集成连接到 GitBook。

## 构建

```text
./gradlew build
```

构建使用官方 Maven 的 CraftEngine 26.9.1 API，加 `-PceVersion=<版本>` 可改为对着别的版本编译。附属通过生成的 Farmersdelight-Plugin-Pro API-only JAR 编译，并要求与本仓库处于同级目录。

## 授权

GNU Affero General Public License v3.0 only，全文见 [LICENSE](LICENSE)。

本插件**完整开源**：配方编辑器、配方关联跳转、手持煎锅烹饪都在本仓库里。允许使用、修改、再分发
（收费分发也可以），前提是满足 AGPL-3.0：保留版权与授权声明、随分发提供完整对应源码、修改版同样以
AGPL-3.0 授权；**如果把修改版作为网络服务提供给他人使用，还要向该服务的使用者提供源码**。

第三方内容（搬运的 Farmer's Delight 素材、shade 进来的库，均为 MIT）保留各自的声明，见
[NOTICE.md](NOTICE.md)。

本仓库是本插件唯一的源码来源。其他人发布的构建产物（无论有没有加过代码）都与作者无关。

## CraftEngine 26.10 快照

26.10 快照会自动启用厨锅原生睡眠/唤醒 ticker；旧版继续使用插件调度器。配方不成立、热源消失或输出受阻时，厨锅在进度归零后睡眠，物品和热源变化或配方重载时唤醒。`/fd stats` 可查看活跃、睡眠以及转换次数。

未发布到 Maven 的快照可通过 `-PceJar=<快照插件JAR>` 和 `-PceLibraries=<CraftEngine/libs目录>` 构建。插件名称更新时会复制旧数据目录，并提供旧插件名的依赖别名。
