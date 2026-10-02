# 随机法术测试台 (Random Spell Test Bench)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.4.0--%2B-green)](https://files.minecraftforge.net/)

> Iron's Spells 'n Spellbooks（铁魔法）附属模组 —— 一个面向**创造模式**的法术测试台，
> 让你方便地随机抽取法术、生成法术卷轴、对比不同等级强度，并搭建训练场地。

| 项目 | 值 |
| --- | --- |
| Mod ID | `randomspellbench` |
| 版本 | `1.0.8` |
| 加载器 | Forge（Forge `47.4.0+`，加载器版本 `[47,)`） |
| Minecraft | `1.20.1`（版本范围 `[1.20.1, 1.21)`） |
| 开源协议 | MIT |
| 作者 | RandomSpellBench Team |
| Issue 反馈 | https://github.com/EntombedShip31/RandomSpellBench/issues |

---

## 前置依赖（必须安装）

本模组依赖以下前置，**请一同放入 `mods` 文件夹**：

| 前置 | 最低版本 | 说明 |
| --- | --- | --- |
| [Iron's Spells 'n Spellbooks](https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks) | `1.20.1-3.15.0+` | 核心法术来源（推荐 `3.16.3`） |
| [Curios](https://www.curseforge.com/minecraft/mc-mods/curios) | `5.4.7+` | 提供法术书槽位 |
| GeckoLib / Player Animator / Caelus | — | Iron's Spells 的运行时前置（随 Iron's Spells 一起安装即可） |

> 说明：Iron's Spells 的 `mods.toml` 已声明 GeckoLib 等为其前置，通常随 Iron's Spells 自动带上。
> 若启动报错缺少上述库，请手动补齐。

---

## 安装

1. 安装对应版本的 Minecraft Forge（`47.4.0+`）。
2. 把下载好的 `randomspellbench-1.0.8.jar` 与前置模组一起放入 `.minecraft/mods/` 目录。
3. 启动游戏，按 **F6 键**（未安装 Iron's Spells 时为 K）或在聊天栏输入 `/rsta config` 打开测试台界面。

---

## 指令

所有指令以 `/rsta` 为前缀。部分指令需要**创造模式**或**管理员权限（op 2）**，
默认仅创造模式可用；使用 `/rsta unlock` 可解除该限制（见下表）。

| 指令 | 权限 | 说明 |
| --- | --- | --- |
| `/rsta` 或 `/rsta help` | 任意 | 显示指令帮助 |
| `/rsta config` | 创造模式 | 打开测试台图形界面 |
| `/rsta randomize` | 创造模式 | 按当前筛选规则随机抽取并分配法术给自己 |
| `/rsta undo` | 创造模式 | 撤销上一次分配 |
| `/rsta extract [hand\|curio]` | 创造模式 | 一键把法术书里的法术拆成卷轴放进背包（默认主手 → 副手 → 饰品栏） |
| `/rsta scroll <法术> [等级 1-20]` | 任意 | 生成对应法术的卷轴（可指定等级） |
| `/rsta scroll all [等级]` | 创造模式 | 给勾选的法术各生成一张卷轴（单次上限 12 张） |
| `/rsta imbue <法术> [等级] [目标]` | 创造模式 | 把法术直接注入目标槽位的武器 / 盔甲 / 饰品，无需奥术铁砧与卷轴 |
| `/rsta unimbue [目标]` | 创造模式 | 清除目标槽位物品上的注入法术（等效原版忏悔石） |
| `/rsta learn <法术> [玩家...]` | 创造模式 / op2（批量） | 学习指定法术；带玩家参数需 op 2 |
| `/rsta unlock [玩家...]` | 自身 / op2（批量） | 解除「仅创造模式可用」限制并持久化（v1.0.8 起死亡重生后依然生效） |
| `/rsta lock [玩家...]` | 自身 / op2（批量） | 恢复「仅创造模式可用」限制 |
| `/rsta reload` | op 2 | 重新加载法术池与配置（会关闭在线玩家已打开的界面） |

> `<法术>` 可用 Tab 补全（取自当前 Iron's Spells 法术池）。

---

## 测试台功能

- **法术池**：中文 / 拼音搜索、按施法类型过滤（全部 / 即时 / 持续 / 长吟）、法术图标与悬浮详情（法力、冷却、施法时间、稀有度）、全选与清除。
- **分配规则**：按权重随机抽取（权重在服务端配置 `spellWeights` 中设置为 `法术id=权重`），可设抽取数量（上限 20，
  对齐 Iron's Spells 原版 `spellbook create` 指令的最大栏位数，旧配置的 12 会在服务端启动时自动迁移），并支持「每学派至少 1 个」。
- **等级规则**：范围内随机，或固定等级以便对比同一法术的不同强度。
- **法术书**：随机取自 Iron's Spells 的法术书，以你的名字命名，并自动装备到 Curios 的 `spellbook` 槽位。
- **勾选批量操作（v1.0.5）**：「生成卷轴 / 注入法术」按左侧**勾选的法术**执行——勾选 1 个即旧的单击行为，
  勾选多个则一键批量：卷轴单次最多 12 张（超出截断并提示，单个网络包一次结算，不卡顿）；
  注入受单物品上限（默认 3）约束，装不下的自动跳过并汇总播报，装备本身的注入法术已超上限时报「注入法术超过装备上限」。
  批量范围**跟随搜索过滤**（v1.0.7）——只作用于当前搜索可见 ∩ 勾选的法术，搜什么测什么；
  左侧标题栏右侧常驻「已勾选 N」小字角标。
- **卷轴自动放主手（v1.0.7）**：生成卷轴默认直接替换主手物品（原主手回背包），测试连发免翻背包；
  可在客户端配置 `ui.autoScrollMainhand` 关闭。
- **选中法术**：生成法术卷轴、长按永久学习、点击思索预览（需 `iss_ponder`）、复现上次结果。
- **注入法术（7 槽位直达）**：把选中法术一键注入主手 / 副手 / 头盔 / 胸甲 / 护腿 / 靴子 / **书**。
  书指 Curios 饰品栏的法术书——点「书」再点「注入法术」会把法术写入书里，书写满后会拒绝写入；
  武器**手持**即可在法术轮盘施放，盔甲与 Curios 饰品需**穿戴**生效，
  注入上限与物品白名单见服务端配置 `[imbue]`。
- **拆下卷轴**：GUI 中「注入法术」与「拆下卷轴」并排成一行，**拆下卷轴**会联动上面选中的槽位——
  选「头」拆下头盔上的法术、选「书」拆下饰品栏法术书里的法术，
  拆出的法术以 ISS 卷轴形式放进背包并从物品上移除（书仍留在槽位，腾出的栏位可继续注入或随机分配）。
  `/rsta extract` 仍保留「整本拆法术书」的快捷方式（v1.0.8 起移除 V 键快捷键）。
- **分配播报**：每次分配结果固定以 actionbar 一行文字播报（v1.0.3 起不再提供开关，始终开启）。

---

## 构建（开发者）

需要 Java 17 与可访问的 Gradle 分发（本项目 `gradle-wrapper.properties` 使用腾讯云镜像以便国内网络）。

```bash
# 用包装器构建（产物：build/libs/randomspellbench-1.0.8.jar）
./gradlew build

# 构建并直接复制到本地 Minecraft 的 mods 目录（默认指向 PCL2 的 1.20.1-Forge 实例）
./gradlew build -Ppcl2_mods_dir=D:/path/to/your/mods
```

构建后也可手动将 `build/libs/randomspellbench-1.0.8.jar` 复制到任意实例的 `mods` 目录。

---

## 开源协议

本模组以 **MIT 协议** 发布，详见 [LICENSE](LICENSE)。
图标 `icon.png` 与源码一并遵循该协议。

欢迎通过 [Issues](https://github.com/EntombedShip31/RandomSpellBench/issues) 反馈问题或建议。
