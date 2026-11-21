# 神化饰品修复 apothiccuriosfix-1.20.1-1.1.0 — 正式版说明

## 文件信息

| 项 | 值 |
|---|---|
| 文件名 | `apothiccuriosfix-1.20.1-1.1.0.jar` |
| 大小 | 252 018 字节 |
| SHA256 | `e7d4715876cfc588ee892d0257afa0e104c26bc243b87314d3ea09395c395408` |
| 环境 | Minecraft 1.20.1 / Forge 47.2.20+ |
| 适用实例 | 涟漪之篇·如涟漪之所见 / 勇者之章3 / 涟漪之篇 0.2.4先行版v2 / 涟漪之篇v3 |

## 依赖

**必需**
- Apotheosis 7.4.x
- Curios API 5.14+

**可选**（缺失时对应功能自动禁用）
- ApothicAttributes（属性伤害词条）
- Iron's Spellbooks（法杖 / 魔法书类目、节奏打击）
- more_mod_tetra（MMT 武器重铸兼容）
- tetra_spell_book（魔法书重铸兼容）

---

## 本版功能

### 一、属性伤害改为玩家独立结算

神化原本只在 `AttributeEvents.meleeDamageAttributes` 里结算火/冷属性伤害，且要求
`source.getEntity()` 是 `LivingEntity`，铁魔法的法术拿不到这个值，所以法术吃不到加成。

本模组改为**完全独立的路径**：

- 监听 `LivingHurtEvent`，只要攻击者是玩家，就读取其 `fire_damage` / `cold_damage` 属性；
- 原伤害结算后，再额外打一段对应类型的伤害；
- `ThreadLocal` 防重入，避免"词条触发词条"；
- 每次施加前把 `invulnerableTime` 归零，避免第二段被无敌帧吃掉；
- 只限玩家，怪物互殴不触发。

效果：近战、法术、弓、召唤物、爆炸都能吃到属性伤害加成。

### 二、技能词条

| 词条 | 适用 | 最低稀有度 | 效果 |
|---|---|---|---|
| **节奏打击** | 全部工具 | 白色 | 10 秒内重复释放同一法术，每层 +10% 法强，最高 5 层（+50%） |
| **天削** | 轻武器、法杖 | 史诗 | 造成伤害时永久削减目标 2% 全属性，最高 10 层（-20%） |
| **弱点破坏** | 全部装备 | 蓝色 | 对无护甲目标造成的伤害 +50% |

**节奏打击**：每次施放都刷新 10 秒计时；换法术或超时则层数归零。

**天削**：层数写入目标持久数据，重登/重载仍保留；属性削减作用于目标全部可同步属性，
最大生命被削后会把血量钳到新上限。

**弱点破坏**：以 `getArmorValue() == 0` 判定无护甲。

### 三、数值词条（法杖 / 魔法书 / 全装备）

| 词条 | 属性 | 数值范围 |
|---|---|---|
| 法术强度 | `spell_power` | 5% ~ 25% |
| 火焰强度 | `fire_spell_power` | 5% ~ 25% |
| 冰霜强度 | `ice_spell_power` | 5% ~ 25% |
| 雷电强度 | `lightning_spell_power` | 5% ~ 25% |
| 神圣强度 | `holy_spell_power` | 5% ~ 25% |
| 末影强度 | `ender_spell_power` | 5% ~ 25% |
| 自然强度 | `nature_spell_power` | 5% ~ 25% |
| 法术抗性 | `spell_resist` | 3% ~ 13% |
| 冷却缩减 | `cooldown_reduction` | 3% ~ 13% |
| 施法速度 | `cast_time_reduction` | 3% ~ 13% |
| 召唤伤害 | `summon_damage` | 5% ~ 25% |

乘区统一为 `MULTIPLY_BASE`，每个稀有度 2 步递进。

### 四、Tetra 系物品重铸兼容

`LootCategory.forItem` 按精确 id 白名单强制归类：

| 模组 | 物品 | 类目 |
|---|---|---|
| MMT | `tetra_iron_staff` / `tetra_dark_wand` | `staff` |
| MMT | `tetra_mmt_bow` | `bow` |
| 铁魔法 | `*_staff` / `staff_of_the_nines` | `staff` |
| tetra_spell_book | `tetra_spell_book` / `novice` / `apprentice` / `archmage` | `spellbook` |
| tetra_spell_book | `tetra_ars_spell_bow` | `bow` |
| tetra_spell_book | `tetra_ars_spell_crossbow` | `crossbow` |

**刻意排除**：
- `tetra_spell_book:tetra_enchanters_sword` —— 缺 `fuller/base_fuller` 图纸，悬停时 Tetra 每次渲染都抛 `InvalidSchematicException`，重铸只会让坏数据更多；
- MMT 的 11 个 `curios_*` 饰品 —— Tetra 模块数据与 Curios 槽定义容易被神化重铸破坏，暂不兼容。

### 五、类目补注册

`irons_apothic` 的数据使用了游戏里不存在的类目名，导致整个词条文件解析失败
（`Unknown element name: staff`）。本模组补注册：

- `staff` / `spellbook` / `light_weapon`
- `curios:ring` / `necklace` / `head` / `hands` / `bracelet` / `feet` / `back` / `charm`

采用幂等注册（已存在则跳过），不会覆盖其它模组已注册的同名类目。

### 六、词条国际名

所有新增词条提供了中英文名称与说明，不再显示裸 key（如
`affix.irons_apothic:staff/skill/rhythm_strike.desc`）。

---

## 已知问题

### 1. MMT 法杖悬停卡顿（非本模组引起）

日志证据：

```
396 x [Render thread/WARN] [se.mickelus.tetra.module.schematic.InvalidSchematicException]:
        modular_enchanters_sword/fuller/base_fuller
```

时间分布为突发式（毫秒级连发），相邻日志还暴露：

```
Failed to create schematic from 'tetra:toolbelt/quiver/quickslot'
Failed to create schematic from 'tetra:toolbelt/storage/quickslot'
Failed to create schematic from 'tetra:sword/more_mod_tetra/mmt_tw...'
```

**结论**：这是 Tetra / MMT / tetra_spell_book 三者之间的图纸数据不匹配，
与本模组无关。本模组在整份日志中零报错。

**建议**：
- 移除 `tetra_spell_book-1.2.1.jar`；或
- 补齐缺失的 `base_fuller` 等图纸；或
- 核对 MMT 版本与 Tetra 版本是否匹配。

### 2. 其它模组报错（与本模组无关）

- `apotheosis_modern_ragnarok` 两个词条文件解析失败 —— 该模组自己的 codec 未注册；
- 冰与火启动期 `NullPointerException: in is null` —— 模型加载问题；
- `Unknown recipe category` —— 其它模组配方分类缺失。

---

## 安装

1. 把 `apothiccuriosfix-1.20.1-1.1.0.jar` 放入 `mods/`；
2. 确保 Apotheosis 与 Curios 已安装；
3. 启动游戏。

## 版本历史

| 版本 | 内容 |
|---|---|
| 1.20.1-1.1.0 | 属性伤害独立结算、三个技能词条、11 个数值词条、Tetra 兼容、类目补注册、词条国际化 |
