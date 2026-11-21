# 神化饰品修复 / ApothicCurios Fix (+ 神化铁魔法 / Iron's Apothic)

Minecraft 1.20.1 / Forge 47.2.20+ 上，让 **Apotheosis 7.4.x 的词条、宝石、属性** 在 **Curios 饰品**
（戒指 / 项链 / 护符 / 腰带 / 书 …）上真正生效，并把 **Iron's Spellbooks** 的法术强度 / 法力消耗词条
移植进神化的词条体系。

- 版本：`1.20.1-1.3.0`
- modid：`apothiccuriosfix`（铁魔法部分是独立的 `irons_apothic` 模块，同一个 jar 内）
- 协议：MIT

---

## 前置

| 模组 | 是否必需 | 说明 |
|---|---|---|
| **Apotheosis 7.4.0+** | **必需** | `mods.toml` 里 `mandatory=true` |
| **Curios 5.14.0+1.20.1** | **必需** | `mods.toml` 里 `mandatory=true` |
| Placebo / ApothicAttributes | 跟随 Apotheosis | Apotheosis 自己的前置 |
| **Iron's Spellbooks** | 可选 | 只有装了它，`irons_apothic` 模块才会初始化 |
| ApothicCurios | 可选 | 不依赖；本模组是它的**替代实现** |
| more_mod_tetra / tetra_spell_book | 可选 | 装了才启用对应的联动修复 |

---

## 一、饰品类目 + 词条属性桥（`apothiccuriosfix`）

### 1. `curios:<slot>` 战利品类目

神化在**数据包重载**时校验词条/宝石 JSON 里的 `types`。出现未知类目（例如 `curios:an_focus`）时
**整个文件会被丢弃**（`Unknown element name: curios:an_focus`）—— 这正是"护符完全没有数值词条"的原因。

本模组注册 Curios API 报告的全部饰品槽 + 一组基础槽位，并在**每次重载**时重新登记；
`mixin.LootCategoryMixin` 还会在 `LootCategory.byId`（codec 用的就是它）里为仍然未知的 `curios:*`
名字**临时创建**类目，所以重载监听器的先后顺序不会影响结果。注册是幂等的：已存在的 id 会被沿用，绝不覆盖。

### 2. 饰品词条属性真正加到玩家身上

Curios 5.14.1 **不会**为饰品槽触发 Forge 的 `ItemAttributeModifierEvent`，所以饰品上的属性词条
过去只能生成一条毫无意义的原版槽 tooltip。本模组：

- 让这些类目声明 `MAINHAND`/`OFFHAND`（**不含 `LEGS`**，这样 ApothicCurios 的假通过逻辑保持为空），
- 由 `event.CurioAffixAttributeBridge` **每秒同步一次**佩戴者的属性修饰符。

### 3. 宝石镶在饰品上

> 摘自 `gem/GemCurioSupport` 的设计说明。

**问题**：神化判定"这颗宝石能不能镶进这件装备"只有一条 ——

```java
LootCategory cat = LootCategory.forItem(itemStack);
return !cat.isNone() && bonusMap.containsKey(cat) && bonusMap.get(cat).supports(rarity);
```

而神化自带宝石的 `bonusMap` 只有 `sword / chestplate / pickaxe …` 这些原版类目，**没有任何宝石带
`curios:*`**，所以饰品永远 `isValidIn == false`，即使类目已经注册好了。

**做法**：不新增宝石、不改任何 JSON，只对几颗**原有**宝石在 `Gem` 的两个方法上补一层回退
（`mixin.GemMixin`）：

- `isValidIn` —— 类目是 `curios:*` 且宝石在白名单里 ⇒ 视为可镶；
- `getBonus` —— 类目是 `curios:*` 且宝石在白名单里 ⇒ 返回挑出来的那个 bonus。

**白名单与映射**（每颗都映射到它自己的**护甲类属性 bonus**，优先 `chestplate/leggings`，其次 `boots`）：

| 宝石 | 用作饰品时的效果 |
|---|---|
| `apotheosis:core/splendor` | 幸运 |
| `apotheosis:core/guardian` | 护甲 |
| `apotheosis:core/brawlers` | 最大生命 |
| `apotheosis:core/ballast` | 击退抗性 |
| `apotheosis:core/tyrannical` | 护甲韧性 |
| `apotheosis:core/lightning` | 移动速度 |

选护甲 bonus 是因为其它 bonus（附魔、药水、伤害减免……）需要额外的运行时钩子，
本模组**只结算属性这一类**。

### 4. tooltip 清理

两层独立实现，任意一层失效另一个仍生效：

- `mixin.client.AttributesLibClientMixin` 在 HEAD 取消
  `AttributesLibClient#applyModifierTooltips`；
- `util.AttributeTooltipFilter` 从最终 tooltip 里删掉同一批"在任意手时 / 穿在脚下时 / …"分组。

盔甲与武器不受影响。

---

## 二、铁魔法词条移植（`irons_apothic` 模块）

> 只有装了 **Iron's Spellbooks** 才会初始化；没装时：不注册 affix codec（于是
> `data/irons_apothic*` 下的词条因为"未知类型"根本不会被加载）、不注册任何引用铁魔法类型的监听器。

提供两类自定义词条：

- `irons_apothic:school_attribute` —— 各法术学派强度（火焰 / 冰霜 / 神圣 / 末影 / 雷霆 / 自然 …）；
- `irons_apothic:mana_cost` —— 各学派法力消耗修正。

另含 `skill` 类词条（`heaven_sever` 断天、`weakness_breaker` 破弱、`rhythm_strike` 节律打击）
与对应的运行时事件（`event.RhythmStrikeEvents` / `event.IronsManaCostHandler`）。

**法术伤害改为玩家独立结算**：神化原本只在 `AttributeEvents.meleeDamageAttributes` 里结算
火/冷属性伤害，且要求 `source.getEntity()` 是 `LivingEntity`，铁魔法的法术拿不到这个值。
本模组改为独立路径，法术也能吃到属性伤害加成。

---

## 与 Apothic Curios / "Apotheosis x Iron's Spellbooks Compat" 的关系

**是整合修改，不是附属。** 不依赖 `apothiccurios`（`mods.toml` 里 `mandatory=false`），
也不依赖任何 "Apotheosis x Iron's Spellbooks Compat" 类模组：

- 饰品类目 / 属性桥 / 宝石支持由本模组自己实现（`category`、`event`、`gem` 各包）；
- 铁魔法词条是本模组内置的 `irons_apothic` 模块（源自 `dev.muon.irons_apothic` 的移植），
  与上游 compat 模组**不要同时安装**，否则会出现同名 affix 类型冲突。

---

## 已知限制

- **宝石只结算属性类 bonus**。需要运行时钩子的 bonus（附魔等级、药水效果、伤害减免、
  受击触发……）不会生效，因为这些钩子在原版装备槽之外没有触发点。
- **宝石白名单是写死的 6 颗**，与饰品槽位的映射也是固定表；没有做成配置。
- 属性同步是**每秒一次**（`CurioAffixAttributeBridge`），换装后最多延迟 1 秒。
- 铁魔法模块要求 **Iron's Spellbooks 3.15.x**；其它版本未验证。
- `curios:*` 类目是"临时创建"的，某些神化内部按类目查表的地方（例如部分战利品逻辑）
  对这些物品的行为未逐一验证。
- 本模组只处理 **饰品**；盔甲/武器的神化行为完全不动。

---

## 安装

1. Minecraft 1.20.1 + Forge 47.2.20 或更高
2. 安装 **Apotheosis 7.4.x**（含 Placebo / ApothicAttributes）与 **Curios 5.14+**
3. 把 `apothiccuriosfix-1.20.1-1.3.0.jar` 放进 `mods/`
4. 想用铁魔法词条再装 **Iron's Spellbooks**

单人/服务端都要装（属性与词条是双端逻辑，但纯服务端也需要客户端有同一份数据包内容）。

---

## 构建

```bash
# JDK 17+、ForgeGradle 6
./gradlew build
# 产物: build/libs/apothiccuriosfix-1.20.1-1.3.0.jar
```

编译期依赖不打进 jar，需要把它们放进项目根目录的 `libs/`（该目录已被 `.gitignore` 忽略）：

```
libs/Apotheosis-1.20.1-7.4.8.jar
libs/Placebo-1.20.1-8.6.3.jar
libs/ApothicAttributes-1.20.1-1.3.7.jar
libs/curios-forge-5.14.1+1.20.1.jar
libs/ApothicCurios-1.20.1-1.0.3e-optimized.jar
libs/[Iron的法术与魔法书] irons_spellbooks-1.20.1-3.15.4.jar
libs/geckolib-forge-1.20.1-4.8.4.jar
```

---

## 协议

[MIT](LICENSE) © 2026 hengx

> 历史设计报告保留在仓库根目录：
> [`报告-apothiccuriosfix-1.20.1-1.1.1.md`](报告-apothiccuriosfix-1.20.1-1.1.1.md)、
> [`报告-apothiccuriosfix-1.20.1-1.1.0.md`](报告-apothiccuriosfix-1.20.1-1.1.0.md)。
