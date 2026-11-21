# 神化饰品修复 apothiccuriosfix-1.20.1-1.1.1 — 正式版说明

## 文件信息

| 项 | 值 |
|---|---|
| 文件名 | `apothiccuriosfix-1.20.1-1.1.1.jar` |
| 大小 | 252 210 字节 |
| SHA256 | `a84632a72fa2dea9f86d97566cf366d270c73fc0cb1b301b27e12f66aba1b3bc` |
| 环境 | Minecraft 1.20.1 / Forge 47.2.20+ |
| 适用实例 | 涟漪之篇·如涟漪之所见 / 勇者之章3 / 涟漪之篇 0.2.4先行版v2 / 涟漪之篇v3 |

## 依赖

**必需**：Apotheosis 7.4.x、Curios API 5.14+

**可选**（缺失时对应功能自动禁用）：ApothicAttributes、Iron's Spellbooks、more_mod_tetra、tetra_spell_book

---

## 本版功能

### 一、词条初始强化等级（按品质）

重铸 / 生成出来的装备，每个词条的 `level` 按该物品品质直接写好：

| 品质 | 词条初始等级 |
|---|---|
| 白 common | **0** |
| 绿 uncommon | **1** |
| 蓝 rare | **2** |
| 紫 epic | **3** |
| 橙 mythic | **4** |
| 远古 ancient | **5** |

- 只改 `level`，**不增删词条、不改可强化次数**；
- 两个 `createLootItem` 重载都挂（3 参数=战利品生成，4 参数=重铸台）；
- 幂等：level 已经是目标值时直接保留原实例，不重建。

### 二、属性伤害改为玩家独立结算

神化原本只在 `AttributeEvents.meleeDamageAttributes` 里结算火/冷属性伤害，
且要求 `source.getEntity()` 是 `LivingEntity`，铁魔法的法术拿不到这个值，法术吃不到加成。

本模组改为独立路径：

- 监听 `LivingHurtEvent`，攻击者是玩家时读取其 `fire_damage` / `cold_damage`；
- 原伤害结算后额外打一段对应类型伤害；
- `ThreadLocal` 防重入，避免词条触发词条；
- 每次施加前 `invulnerableTime = 0`，避免被无敌帧吃掉；
- 只限玩家，怪物互殴不触发。

效果：近战、法术、弓、召唤物、爆炸都能吃到属性伤害加成。

### 三、技能词条

| 词条 | 适用 | 最低稀有度 | 效果 |
|---|---|---|---|
| **节奏打击** | 全部工具 | 白色 | 10 秒内重复释放同一法术，每层 +10% 法强，最高 5 层（+50%） |
| **天削** | 轻武器、法杖 | 史诗 | 造成伤害时永久削减目标 2% 全属性，最高 10 层（-20%） |
| **弱点破坏** | 全部装备 | 蓝色 | 对无护甲目标造成的伤害 +50% |

- **节奏打击**：每次施放刷新 10 秒计时；换法术或超时则层数归零；
- **天削**：层数写入目标持久数据，重登/重载保留；最大生命被削后血量钳到新上限；
- **弱点破坏**：以 `getArmorValue() == 0` 判定无护甲。

### 四、数值词条（11 个）

| 词条 | 属性 | 范围 |
|---|---|---|
| 法术强度 / 火焰 / 冰霜 / 雷电 / 神圣 / 末影 / 自然 | `*_spell_power` | 5% ~ 25% |
| 法术抗性 | `spell_resist` | 3% ~ 13% |
| 冷却缩减 | `cooldown_reduction` | 3% ~ 13% |
| 施法速度 | `cast_time_reduction` | 3% ~ 13% |
| 召唤伤害 | `summon_damage` | 5% ~ 25% |

乘区 `MULTIPLY_BASE`，每稀有度 2 步递进，覆盖全部装备类目。

### 五、Tetra 系物品重铸兼容

按精确 id 白名单强制归类：

| 模组 | 物品 | 类目 |
|---|---|---|
| MMT | `tetra_iron_staff` / `tetra_dark_wand` | `staff` |
| MMT | `tetra_mmt_bow` | `bow` |
| 铁魔法 | `*_staff` / `staff_of_the_nines` | `staff` |
| tetra_spell_book | 4 本魔法书 | `spellbook` |
| tetra_spell_book | `tetra_ars_spell_bow` | `bow` |
| tetra_spell_book | `tetra_ars_spell_crossbow` | `crossbow` |

**刻意排除**：
- `tetra_spell_book:tetra_enchanters_sword` —— 缺 `fuller/base_fuller` 图纸，重铸只会加重损坏；
- MMT 的 11 个 `curios_*` 饰品 —— 避免 Tetra 模块数据被神化重铸破坏。

### 六、类目补注册

`irons_apothic` 使用了游戏里不存在的类目名，导致整个词条文件解析失败
（`Unknown element name: staff`）。本模组幂等补注册：

- `staff` / `spellbook` / `light_weapon`
- `curios:ring` / `necklace` / `head` / `hands` / `bracelet` / `feet` / `back` / `charm`

### 七、词条国际化

所有新增词条提供中英文名称与说明，不再显示裸 key。

---

## 已知问题（均与本模组无关）

### 1. MMT 法杖悬停卡顿

```
396 x [Render thread/WARN] [se.mickelus.tetra.module.schematic.InvalidSchematicException]:
        modular_enchanters_sword/fuller/base_fuller
```

相邻日志还有：

```
Failed to create schematic from 'tetra:toolbelt/quiver/quickslot'
Failed to create schematic from 'tetra:toolbelt/storage/quickslot'
Failed to create schematic from 'tetra:sword/more_mod_tetra/mmt_tw...'
```

这是 Tetra / MMT / tetra_spell_book 之间的图纸数据不匹配，本模组在整份日志中零报错。

**建议**：移除 `tetra_spell_book-1.2.1.jar`，或补齐缺失图纸，或核对 MMT 与 Tetra 版本。

### 2. 其它模组报错

- `apotheosis_modern_ragnarok` 两个词条文件解析失败（该模组 codec 未注册）
- 冰与火启动期 `NullPointerException: in is null`（模型加载）
- `Unknown recipe category`（其它模组配方分类缺失）

---

## 安装

1. 把 `apothiccuriosfix-1.20.1-1.1.1.jar` 放入 `mods/`；
2. 确保 Apotheosis 与 Curios 已安装；
3. 启动游戏。

## 版本历史

| 版本 | 内容 |
|---|---|
| 1.20.1-1.1.1 | 补回「词条初始强化等级按品质」（1.1.0 遗漏） |
| 1.20.1-1.1.0 | 属性伤害独立结算、技能词条、数值词条、Tetra 兼容、类目补注册、词条国际化 |