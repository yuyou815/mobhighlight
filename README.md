# Mob Highlight（生物发光轮廓）

给半径内的生物叠加**原版实体发光轮廓**（光灵箭那种：贴着模型、可透视的描边），
按规则着色，支持其他模组添加的生物，带可视化配置界面。

- Minecraft **1.21.11** · Fabric Loader `0.19.5` · fabric-api `0.141.6+1.21.11`
- Loom `1.17.21` + Gradle `9.5.0` · **JDK 21** · Mojang 官方映射
- 纯客户端、零 Mixin、无需中继服务端安装

> 本模组不画碰撞箱。发光轮廓走的是原版 outline 管线，轮廓贴合实体模型本身。

## 1. 操作

| 操作 | 说明 |
|---|---|
| **H** | 开关发光轮廓（总开关），聊天栏反馈「已开启 / 已关闭」 |
| **J** | 打开配置界面 |
| **]** / **[** | ±8 格调整探测半径 |
| 常规页「快捷键设置」 | 跳到原版控制设置，可改 H / J 等按键 |
| `/mobhl` 或 `/mobhl config` | 打开配置界面 |
| `/mobhl status` | 打印当前配置摘要 |

> 为什么不是 Ctrl+H：1.21.11 的 `KeyMapping` 只保存单个按键、不保存修饰键，
> 按下 Ctrl+H 时绑到 H 的键同样会被触发，两者无法干净区分。所以改成 H / J 两个独立键。

### 1.1 穿墙显示

| 选项 | 默认 | 说明 |
|---|---|---|
| 穿墙显示 | 开 | 开着就是原版发光的效果 —— 隔着方块也能看到轮廓，零额外开销。关掉后只给视线可见的生物描边，每帧会对候选实体做 `BlockGetter#clip` 射线检测，并用 `maxOcclusionRays`（默认 64，可在 json 里改）限制每帧射线条数，超预算的直接跳过 |

> 视线检测用的是 `ClipContext.Block.VISUAL`（方块的视觉形状），因此玻璃这类透明方块同样算遮挡。

所有改动即时写入 `config/mobhighlight.json`，重启游戏后继续生效。

## 2. 目录结构

```
src/main/java/com/example/mobhighlight/
├── MobHighlight.java            MOD_ID + 日志
├── MobHighlightClient.java      唯一入口：集中注册配置 / 渲染 / 按键 / 命令 / 校正扫描
├── config/
│   ├── Faction.java             默认规则的七个分类 + 默认颜色
│   └── ModConfig.java           JSON 规则配置 + 读写（含玩家自建规则）
├── core/
│   ├── FactionResolver.java     实体类型 → 默认规则分类（动态，无硬编码名单）
│   ├── HighlightRules.java      最终「该不该发光 + 什么颜色」裁决表（带缓存）
│   └── GlowApplier.java         改写 EntityRenderState#outlineColor —— 渲染本体
├── input/ModKeyBindings.java    H = 开关、J = 设置、[ / ] 调半径
├── command/ModCommands.java     /mobhl ...
└── ui/
    ├── ConfigScreen.java        五个页签的配置界面（纯原版 widget，无第三方依赖）
    └── CustomRuleScreen.java    编辑一条自定义规则：改名 / 换色 / 两级挑实体
```

## 3. 核心原理

### 3.1 直接用原版的描边管线

1.21.9 重做渲染后，实体发光轮廓的数据通路是：

```
EntityRenderState.outlineColor (public int)
        │  LevelRenderState.haveGlowingEntities (public boolean) 必须为 true
        ▼
EntityRenderDispatcher.submit(...)  →  模型额外画进 entityOutlineTarget
        ▼
ENTITY_OUTLINE_POST_CHAIN 后处理叠加到主画面 → 透视可见的描边
```

所以本模组在抽取阶段只做一件事：**给目标状态的 `outlineColor` 赋 ARGB 颜色，
并把 `haveGlowingEntities` 置为 true**，不存在自己画几何体的部分。

### 3.2 为什么必须是 `WorldRenderEvents.END_EXTRACTION`

`LevelRenderer#renderLevel` 的实际顺序：

```
extractVisibleEntities                     ← 原版抽取实体，顺手算出 distanceToCameraSq
  ↓
WorldBorderRenderer.extract                ← END_EXTRACTION 就挂在它后面
  ↓
读 haveGlowingEntities → 决定是否把轮廓后处理链挂进 frame graph
  ↓
addMainPass → submitEntities
```

`END_EXTRACTION` 是唯一既「拿得到全部已抽取状态」、又「赶在原版做描边决策之前」的时机；
换成 `BEFORE_ENTITIES` 就太晚了 —— 那时 frame graph 已经定好。

另外 `submitEntities` 里有这么一段：

```java
if (!levelRenderState.haveGlowingEntities) {
    state.outlineColor = 0;   // 只在「没有发光实体」时才清零
}
```

这解释了为什么两个字段必须同时设置：只改颜色会被这里抹掉。

### 3.3 规则判定（完全动态，兼容模组生物）

`HighlightRules` 的优先级：

1. **命名空间**：某个模组被整体排除时不发光。
2. **单个实体「永不发光」**（实体清单页的三态之一）。
3. **自定义规则**：被挑走的实体用它自己的颜色和开关（见 §3.4）。
4. **默认规则**：该分类是否启用 + 分类颜色。分七个类 ——
   玩家 / 友好 / 中立 / 敌对 / 水生 / 环境 / 其他。
5. **单个实体「强制发光」**：保留所属规则的颜色，只跳过开关。

默认规则的分类由 `FactionResolver` 判定，**不含任何原版生物名单**：

1. 标签：`c:neutral` / `c:hostile` / `c:passive`（Fabric 约定标签）、
   `minecraft:undead`、`minecraft:raiders`；标签不存在时自动跳过。
2. 注册时的 `MobCategory`（模组注册生物时通常都会填）。
3. 低频校正：每隔 `scanIntervalMs`（默认 250ms）扫一次半径内的真实实体，
   实现了 `NeutralMob` 的算中立、实现了 `Enemy` 的算敌对 ——
   给「类别填成 MISC 的模组生物」兜底，也是本模组唯一一次遍历世界。

结果按 `EntityType` 缓存，渲染时只做 Map 查找。

### 3.4 自定义规则

默认规则是「实体按标签 / 类别自动归类」，自定义规则反过来 —— **由玩家自己挑实体**：

- **两级选择**。点「添加实体」先进一级菜单，列的是**默认规则已经分好的七个分类**，
  每行带 `已选/总数` 计数；点进某个分类才是二级菜单：该分类下的实体列表，
  可用搜索框再过滤，点条目即加入。两级都动态生成自 `BuiltInRegistries.ENTITY_TYPE`，
  **覆盖所有实体**（含其他模组的）。
- **规则之间互斥**。一个实体只能属于一条自定义规则：已被别的规则选走的显示为灰色不可点，
  tooltip 提示它现在属于哪条规则 —— 想换就先去那条规则里把它移出。
- **覆盖与恢复**。加入后实体改用这条规则的颜色与开关，覆盖「默认规则」页给它的设置；
  移出实体或删掉整条规则，它会**立刻恢复**成默认规则的分类与颜色。
  这条说明常驻印在「自定义规则」页上，页签悬停另有完整版。
- 编辑页里能改名、点色块轮换颜色、勾选是否启用（关掉即整条规则不生效）。
- 典型用法：村民这类 `MobCategory = MISC`、又没有标签的实体，默认规则只能落到「其他」，
  单独建一条规则把它挑出来最省事。

### 3.5 性能

- **不每帧遍历世界**：直接复用原版抽取结果，那些实体已经过了视锥剔除与渲染距离剔除，
  且每个自带 `distanceToCameraSq`，半径剔除是免费的。
- 每帧开销 = O(可见实体数) 次 Map 查找 + 一次平方距离比较。
- `maxGlow`（默认 128）是硬上限，实体堆场里到量就停。
- 只有关掉「穿墙显示」才会多出射线检测开销，由 `maxOcclusionRays`（默认 64 条/帧）封顶。
- 规则判定与最终裁决都按 `EntityType` 缓存，改动配置时才 `invalidate()`。

## 4. 配置

`config/mobhighlight.json`（节选）：

```jsonc
{
  "enabled": true,             // 总开关
  "radius": 32.0,              // 探测半径（格）
  "maxGlow": 128,              // 单帧最多描边数
  "ignoreInvisible": true,     // 跳过隐身实体
  "glowSelf": false,           // 第三人称下给自己描边
  "scanIntervalMs": 250,       // 分类校正扫描间隔
  "factions": { "hostile": { "enabled": true, "color": 0xFF3B30 }, "...": {} },
  "customFactions": [          // 玩家自建规则
    { "id": "g1a2b3c", "name": "村民", "color": 0xFFD233, "enabled": true,
      "members": ["minecraft:villager", "minecraft:wandering_trader"] }
  ],
  "typeOverrides": { "minecraft:bat": "off" },   // 只对「不跟随规则」的条目落盘
  "deniedNamespaces": ["twilightforest"]          // 按模组来源整包排除
}
```

界面五个页签：

- **常规**：总开关、穿墙显示、隐身跳过、自身描边、半径、上限、校正间隔、快捷键设置、恢复默认
- **默认规则**：七个自动归类分类各自的开关 + 色块（点击在预设色之间轮换）
- **自定义规则**：新建 / 改名 / 换色 / 开关；点规则名进入编辑页挑实体（见 §3.4）
- **实体清单**：动态生成自 `BuiltInRegistries.ENTITY_TYPE`，含模组生物；支持搜索、
  按命名空间筛选、分页；每个条目三态循环 —— 跟随规则 / 强制发光 / 永不发光
  （`*` 前缀表示已加入某条自定义规则）
- **模组来源**：按 namespace 整体开关（同时也是兼容性出问题时的紧急开关）

## 5. 命令

```
/mobhl                      打开配置界面
/mobhl toggle               总开关
/mobhl radius [blocks]      查看 / 设置半径（4~128）
/mobhl max <count>          单帧上限
/mobhl scan <millis>        分类校正间隔
/mobhl rule <id>            切换某个默认规则分类的开关
                            （player/friendly/neutral/hostile/aquatic/ambient/other）
/mobhl reload               重新读取配置文件
/mobhl reset                恢复默认
/mobhl status               打印配置摘要
```

## 6. 已知限制

1. **没提供 outline 变体的实体渲染器会抛异常**。
   `OutlineBufferSource.getBuffer` 在 RenderType 既不是 outline 类型、又没有对应 outline 类型时
   直接 `throw new IllegalStateException("Can't render an outline for this rendertype!")`。
   原版发光效果有同样的约束，只是本模组会同时标记更多实体、更容易触发。
   遇到时：在游戏日志里搜 `Can't render an outline`，到「模组来源」页关掉那个 namespace 即可。
2. `minecraft:player` 无法区分自己还是别人，默认以「距离相机 < 1 格」判定为自身并跳过，
   需要给自己描边时把 `glowSelf` 设成 true。
3. GUI 分层渲染后「每帧一次」的 API 不幂等：`Screen#renderWithTooltipAndSubtitles`
   本来就会先调 `renderBackground(...)`，所以自定义 Screen **不能在自己的 `render()` 里再调**
   —— 重复触发 `blurBeforeThisStratum` 会抛 `IllegalStateException: Can only blur once per frame`。

## 7. 构建

需要 **JDK 21** 与 **Gradle 9.x**（Loom 1.17 要求 Gradle 9 及以上）。在项目根目录执行：

```bash
gradle build
```

产物：`build/libs/mobhighlight-<version>.jar`，文件名里的版本号取 `gradle.properties` 的 `mod_version`。

三个容易踩的点：

- 插件 id 必须是 `fabric-loom`。写成 `net.fabricmc.fabric-loom` 会切到 26.x 的 no-remap 模式，
  报 `Cannot use Mojang mappings in a non-obfuscated environment`。
- 若你的 Gradle 用户目录（`GRADLE_USER_HOME`，默认 `~/.gradle`）下的 `gradle.properties`
  写死了代理地址，需覆盖环境变量或改掉该文件，否则 Fabric Maven 拉不到依赖。
- Loom 反编译 Minecraft 比较吃内存，内存不足时在上述文件中加 `org.gradle.jvmargs=-Xmx3G`。
