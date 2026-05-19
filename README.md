# FarmersDelight Wiki

FarmersDelight 是一个基于 CraftEngine 26.5 的 Farmer's Delight 风格玩法插件。CraftEngine 负责自定义物品、方块、家具、模型、字体图片和标签，本插件负责把这些资源接成真正能玩的服务端逻辑。

## 1. 插件做了什么

本插件新增并实现了这些系统：

- 厨锅：6 个原料槽、容器槽、待输出槽、烹饪进度、世界粒子、打包掉落、内容提示；漏斗交互可配置开启。
- 砧板：放置食材、小刀/工具处理、概率多输出、配方查看；可配置是否允许副手小刀/副手食材。
- 炉灶：基于原版营火配方烹饪，带 CE 物品显示。
- 煎锅：基于原版营火配方烹饪，带 CE 物品显示；默认不通过漏斗传导热量。
- 作物与采集：水稻、野生水稻、高作物、成熟收割、小刀收割草秆。
- 小刀战利品：用小刀击杀成年生物时额外掉落火腿、皮革、羽毛等，可配置概率和抢夺加成。
- 食物效果：宠物食物、营养效果、容器返还。
- 配方查看 GUI：厨锅和砧板配方列表、详情页、标签/多选原料展示。
- 进度系统：内置 Farmer's Delight 风格进度数据包。

## 2. 安装环境

- Java 21
- Paper / Purpur 1.21+
- CraftEngine 26.5

安装步骤：

1. 将 `farmersdelight-1.0.0.jar` 放入服务器 `plugins` 目录。
2. 安装 CraftEngine 26.5。
3. 放入本插件配套的 CraftEngine 资源配置。
4. 启动服务器，等待生成配置文件。
5. 修改配置后可使用 `/fd reload`；修改 jar、CraftEngine 资源或资源包后建议完整重启服务器。

生成文件：

```text
plugins/FarmersDelight/config.yml
plugins/FarmersDelight/gui.yml
plugins/FarmersDelight/recipes/cooking_pot_recipes.yml
plugins/FarmersDelight/recipes/cutting_board_recipes.yml
plugins/FarmersDelight/block_storage.yml
```

## 3. CraftEngine 侧需要配置什么

CraftEngine 侧负责“资源长什么样、叫什么、有哪些标签”。本插件通过 CE ID 调用这些资源。

通常需要在 CraftEngine 资源包里准备：

- 物品：小刀、食材、熟食、容器、GUI 图标、进度图标等。
- 方块/家具：厨锅、砧板、炉灶、煎锅、托盘、水稻、野生作物、蘑菇群落、绳子、榻榻米等。
- 标签：例如 `farmersdelight:knives`、`farmersdelight:heat_sources`。
- 语言：物品名称和 lore 建议写在 CE 的语言/显示配置里，插件 GUI 会优先读取物品自己的显示名称。
- 图片字体：`gui.yml` 里使用的 `<image:farmersdelight:cooking_pot_gui>`、`<image:farmersdelight:recipelist>` 等需要在 CE 资源侧存在。

本插件注册的主要行为 ID：

```text
farmersdelight:cooking_pot
farmersdelight:cutting_board
farmersdelight:skillet
farmersdelight:stove
farmersdelight:tall_crop
farmersdelight:wild_rice
farmersdelight:rope
farmersdelight:tatami
farmersdelight:mushroom_colony
farmersdelight:upper_half_loot_relay
```

CE 方块或家具配置中需要把对应行为挂到对应资源上。例如厨锅挂 `farmersdelight:cooking_pot`，砧板挂 `farmersdelight:cutting_board`，炉灶挂 `farmersdelight:stove`。CE 配置语法以 CraftEngine 26.5 的资源配置为准。

## 4. 配置文件分工

`config.yml` 是玩法规则配置。现在所有可改玩法选项都会写出来，常用项排在前面，高级项排在后面。

`gui.yml` 只负责 GUI：标题、布局、按钮、背景、图标、进度条、配方页面。主配置不再读取 GUI 小节。

`recipes/cooking_pot_recipes.yml` 是厨锅配方。

`recipes/cutting_board_recipes.yml` 是砧板配方。

## 5. 常用配置

语言：

```yaml
language: zh_cn
```

砧板副手交互：

```yaml
cutting-board:
  allow-offhand-interactions: false
```

`false` 更接近原模组，只按主手工具处理。`true` 允许副手小刀 + 主手物品，或主手小刀 + 副手物品。

煎锅漏斗导热：

```yaml
heat-sources:
  skillet:
    allow-conductors: false
```

默认关闭。厨锅仍可使用导热方块判定热源，煎锅不会因为漏斗在下方就被当成加热。

厨锅内容物品提示：

```yaml
cooking-pot-packed-drop:
  enabled: true
  durability-bar:
    enabled: true
  hide-advanced-durability-tooltip:
    enabled: true
```

开启后厨锅被打包成物品时会保存内部内容，并用耐久条表示占用情况，同时隐藏原版高级耐久提示。

## 6. 物品 ID 与清空写法

物品 ID 可以写原版或 CE 物品：

```yaml
minecraft:bowl
farmersdelight:flint_knife
```

可选物品输出可以写下面任意值来清空：

```yaml
""
none
null
empty
air
minecraft:air
```

适用位置：

- `knife-drops.<entity>.normal`
- `knife-drops.<entity>.burning`
- `straw-drops.<type>.drop`
- `container-returns.<item>`
- `gui.yml` 中可选展示物品

配方的 `result` 不建议清空；如果不要某个配方，直接删除那条配方。

## 7. 厨锅

厨锅有 6 个原料槽、1 个容器槽、1 个待输出槽。配方匹配成功并且有热源时开始烹饪，完成后产物进入待输出槽。

漏斗逻辑默认关闭。CraftEngine 26.x 的方块容器桥接仍在适配中，需要测试时再开启：

```yaml
hopper-interactions:
  enabled: false
  cooking-pot: true
  cutting-board: true
```

开启后的设计逻辑：

- 上方漏斗：向原料槽塞入原料。
- 侧面漏斗：主要向容器槽塞容器，也允许按容器/输出规则交互。
- 下方漏斗：从待输出槽抽取成品。

拿容器右键厨锅取出待输出槽物品时，只处理输出和容器，不会刷新整锅数据导致其它内容丢失。

厨锅配方示例：

```yaml
cooking_pot_recipes:
  vegetable_soup:
    ingredients:
      - "minecraft:carrot"
      - "minecraft:potato"
      - "minecraft:beetroot"
      - "farmersdelight:cabbage|farmersdelight:cabbage_leaf"
    container: "minecraft:bowl"
    result: "farmersdelight:vegetable_soup"
    result-count: 1
    experience: 1.0
    cook-time: 200
    category: meals
```

原料写法：

- 普通物品：`minecraft:carrot`
- CE 物品：`farmersdelight:cabbage`
- 标签：`#farmersdelight:vegetables`
- 同一格任选：`farmersdelight:cabbage|farmersdelight:cabbage_leaf`
- 排除：`#namespace:tag,!namespace:item,!#namespace:other_tag`

## 8. 砧板

砧板可以放上物品，再用配置的小刀或工具处理。输出支持多个结果和概率。

砧板配方示例：

```yaml
cutting_board_recipes:
  raw_pasta:
    input: "farmersdelight:wheat_dough"
    tool: "#farmersdelight:knives"
    results:
      - item: "farmersdelight:raw_pasta"
        count: 1
```

多个概率输出：

```yaml
cutting_board_recipes:
  wild_tomatoes:
    input: "farmersdelight:wild_tomatoes"
    tool: "#farmersdelight:knives"
    results:
      - item: "farmersdelight:tomato_seeds"
      - item: "farmersdelight:tomato"
        chance: 0.2
      - item: "minecraft:green_dye"
        chance: 0.1
```

工具可以写单个 `tool`，也可以写多个 `tools`。

## 9. 热源

热源可来自原版方块、原版标签、CE 方块、CE 标签。

```yaml
heat-sources:
  tags:
    - farmersdelight:heat_sources
  vanilla-blocks:
    - minecraft:magma_block
    - minecraft:lava_cauldron
    - minecraft:lava
    - minecraft:fire
    - minecraft:soul_fire
  vanilla-tags:
    - minecraft:campfires
  custom-blocks:
    - farmersdelight:stove[fire:true]
  conductors:
    - minecraft:hopper
  conductor-tags: []
```

如果 CE 资源侧已经给某个自定义方块加了 `farmersdelight:heat_sources` 标签，这里不用再单独写进 `custom-blocks`。

## 10. GUI

GUI 配置在 `gui.yml`。每个界面都有：

- `title`：标题，支持 MiniMessage 和 CE 图片字体。
- `title-layout`：配合 CE 图片字体做标题背景。
- `rows`：行数。
- `layout`：每个字符代表一个槽位。
- `legend`：字符到槽位类型的映射。
- `items`：按钮或装饰物品。

GUI 物品写法：

```yaml
items:
  recipe:
    material: KNOWLEDGE_BOOK
    name: "查看烹饪配方"
    lore:
      - "点击此处查看配方"
  arrow:
    item: farmersdelight:cooking_time
    name: "烹饪过程"
```

`material` 使用 Bukkit 原版材质，`item` 使用 CraftEngine 物品。配方详情页会尽量读取展示物品自己的名称，所以 CE 侧物品名称写好后，配方页面也会更自然。

## 11. 小刀掉落与草秆

小刀识别：

```yaml
knife-config:
  tags:
    - farmersdelight:knives
  items:
    - farmersdelight:flint_knife
    - farmersdelight:iron_knife
    - farmersdelight:golden_knife
    - farmersdelight:diamond_knife
    - farmersdelight:netherite_knife
```

小刀击杀额外掉落：

```yaml
knife-drops:
  pig:
    normal: farmersdelight:ham
    burning: farmersdelight:smoked_ham
    chance: 0.5
    looting-multiplier: 0.1
```

草秆掉落：

```yaml
straw-drops:
  mature_wheat:
    drop: farmersdelight:straw
    min-amount: 1
    max-amount: 2
```

## 12. 食物效果

宠物食物：

```yaml
pet-foods:
  farmersdelight:dog_food:
    entities:
      - WOLF
    require-tamed: true
    restore-health: true
    effects:
      - type: SPEED
        duration: 6000
        amplifier: 0
        ambient: false
        particles: true
```

营养效果默认关闭：

```yaml
nourishment-foods:
  enabled: false
  foods:
    farmersdelight:noodle_soup:
      duration: 300
```

容器返还：

```yaml
container-returns:
  farmersdelight:milk_bottle: minecraft:glass_bottle
```

## 13. 命令与权限

命令：

```text
/fd help
/fd recipe
/fd recipe cooking_pot
/fd recipe cutting_board
/fd reload
/fd reload all
/fd reload config
/fd reload gui
/fd reload lang
/fd reload recipes
/fd reload advancements
/fd cleanup
```

权限：

```text
farmersdelight.command
farmersdelight.command.recipe
farmersdelight.use.cooking_pot
farmersdelight.use.stove
farmersdelight.use.skillet
farmersdelight.use.cutting_board
farmersdelight.admin
farmersdelight.*
```

## 14. 重载建议

`/fd reload` 默认只重载常规玩法配置，相当于 `/fd reload config`。可用的重载命令：

- `/fd reload config`：重载 `config.yml`。
- `/fd reload gui`：重载 `gui.yml`。
- `/fd reload lang`：重载语言文件。
- `/fd reload recipes`：重载厨锅和砧板配方，并刷新 CE 物品缓存。
- `/fd reload advancements`：重载进度数据包。
- `/fd reload all`：完整重载本插件配置、GUI、语言、配方和进度。

建议完整重启：

- 更新 jar
- 更新 CraftEngine
- 修改 CE 资源配置
- 修改资源包字体、模型、贴图

不建议用 PlugMan 或 Bukkit reload 热重载本插件和 CraftEngine。

## 15. 常见问题

CE 物品名称显示成 ID：检查 CE 物品的显示名、语言文件和资源包是否加载。本插件配方页会优先使用物品本身的名称。

配方详情里原料展示不好看：先确认 CE 侧物品名是否正常，再看 `gui.yml` 的详情页布局和 `show-ingredient-ids`。

漏斗没有按预期工作：先确认 `hopper-interactions.enabled` 是否开启。当前默认关闭；开启后再确认方向，厨锅上方塞原料，下方抽成品，侧面塞容器。煎锅默认不接受漏斗导热。

修改配置后没变化：先 `/fd reload`。如果动了 CE 资源或资源包，完整重启。

## 16. 编译

开发环境需要本地存在 CraftEngine 26.5 构建产物，路径参考 `build.gradle.kts`。

```powershell
.\gradlew.bat clean build
```

输出：

```text
build/libs/farmersdelight-1.0.0.jar
```

可选混淆构建：

```powershell
.\gradlew.bat clean build -Pobfuscate=true
```

输出：

```text
build/libs/farmersdelight-1.0.0-obf.jar
```

混淆构建会保留 Bukkit 主类和公开 API 包，避免外部插件调用入口被改名。
