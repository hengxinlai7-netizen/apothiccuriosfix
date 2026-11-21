#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the curio_affixes_plus data pack (apotheosis:attribute affixes for every curio slot)
and the matching lang files for the patch mod.

Every slot gets five distinct attribute affixes with all six vanilla rarities, which is what
Apotheosis' "Insufficient number of affixes to satisfy the loot rules" check wants (the highest
requirement seen in the packs is 5).  Run from the project root:

    python tools/gen_curio_affixes_plus.py
"""
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# The affixes ship INSIDE the mod jar: <jar>/data/apothiccurios/affixes/... . "apothiccurios" is the data
# namespace of the Apothic Curios mod (its own example pack uses it too); this patch mod's id is
# "apothiccuriosfix", so the two never collide, and every file name below ends in "_plus" anyway.
# --no-irons writes a variant that only uses vanilla/Forge attributes, for the isolated test server that
# cannot load irons_spellbooks (its fallen_lib needs a newer Forge build).
NO_IRONS = "--no-irons" in sys.argv
NS = "apothiccurios"
RESOURCES = os.path.join(ROOT, "src", "main", "resources")
if NO_IRONS:
    DATA_DIR = os.path.join(ROOT, "datapack", "curio_affixes_plus_selftest", "data", NS, "affixes")
    LANG_DIR = None
else:
    DATA_DIR = os.path.join(RESOURCES, "data", NS, "affixes")
    LANG_DIR = os.path.join(RESOURCES, "assets", NS, "lang")

# attribute id -> (operation, common min, common step, en name, en suffix, zh name, zh suffix)
ATTRS = {
    "minecraft:generic.max_health":        ("ADDITION", 1.0, 0.5, "Vital",     "of Vitality",   "生命",   "·活力"),
    "minecraft:generic.attack_damage":     ("ADDITION", 0.5, 0.25, "Mighty",   "of Might",      "强袭",   "·力量"),
    "minecraft:generic.armor":             ("ADDITION", 1.0, 0.5, "Armored",   "of Armor",      "护甲",   "·壁垒"),
    "minecraft:generic.armor_toughness":   ("ADDITION", 0.5, 0.25, "Reinforced","of Toughness", "坚韧",   "·韧性"),
    "minecraft:generic.knockback_resistance": ("ADDITION", 0.02, 0.005, "Steadfast", "of Steadfastness", "稳固", "·坚定"),
    "minecraft:generic.movement_speed":    ("MULTIPLY_TOTAL", 0.01, 0.005, "Swift",  "of Swiftness",  "迅捷", "·疾行"),
    "minecraft:generic.attack_speed":      ("MULTIPLY_TOTAL", 0.02, 0.01, "Quick",   "of Haste",      "灵敏", "·急速"),
    "minecraft:generic.luck":              ("ADDITION", 0.5, 0.25, "Lucky",     "of Fortune",    "幸运",   "·幸运"),
    "minecraft:generic.attack_knockback":  ("ADDITION", 0.1, 0.05, "Heavy",     "of Impact",     "沉重",   "·冲击"),
    "irons_spellbooks:spell_power":        ("MULTIPLY_BASE", 0.02, 0.01, "Arcane", "of Spell Power", "奥术", "·法强"),
    "irons_spellbooks:max_mana":           ("ADDITION", 15.0, 5.0, "Mana-Touched", "of Mana",   "法力",   "·法力"),
    "forge:step_height_addition":          ("ADDITION", 0.1, 0.05, "Nimble",    "of Striding",   "矫健",   "·跨越"),
    "forge:swim_speed":                    ("MULTIPLY_BASE", 0.05, 0.02, "Aquatic", "of the Tides", "游鱼", "·潮汐"),
}

# Substitution used by the --no-irons variant so the same slot/attribute layout is testable without
# irons_spellbooks installed.
IRONS_SUBSTITUTE = {
    "irons_spellbooks:spell_power": "minecraft:generic.attack_damage",
    "irons_spellbooks:max_mana": "minecraft:generic.armor",
}

SLOT_ATTRS = {
    "ring":       ["max_health", "attack_damage", "luck", "attack_speed", "attack_knockback"],
    "necklace":   ["armor", "armor_toughness", "knockback_resistance", "max_health", "attack_damage"],
    "charm":      ["spell_power", "max_mana", "max_health", "attack_damage", "luck"],
    "belt":       ["movement_speed", "step_height", "swim_speed", "max_health", "attack_speed"],
    "rune":       ["spell_power", "max_mana", "max_health", "armor", "attack_damage"],
    "spellbook":  ["spell_power", "max_mana", "max_health", "armor", "movement_speed"],
    "an_focus":   ["spell_power", "max_mana", "max_health", "attack_damage", "luck"],
    "back":       ["armor", "movement_speed", "step_height", "max_health", "knockback_resistance"],
    "hands":      ["attack_damage", "attack_speed", "max_health", "armor", "luck"],
    "feet":       ["movement_speed", "step_height", "swim_speed", "armor", "max_health"],
    "head":       ["max_health", "luck", "armor", "attack_damage", "attack_knockback"],
    "body":       ["armor", "armor_toughness", "max_health", "knockback_resistance", "attack_knockback"],
    "bracelet":   ["attack_damage", "armor", "max_health", "luck", "attack_speed"],
    "spellstone": ["spell_power", "max_mana", "max_health", "armor", "luck"],
    "scroll":     ["spell_power", "max_mana", "max_health", "movement_speed", "luck"],
    "talisman":   ["max_health", "luck", "armor", "knockback_resistance", "attack_knockback"],
    "brooch":     ["spell_power", "max_health", "armor", "luck", "attack_damage"],
    "aether_ring":       ["max_health", "attack_damage", "luck", "movement_speed", "armor"],
    "aether_pendant":    ["max_health", "armor", "spell_power", "luck", "movement_speed"],
    "aether_cape":       ["armor", "movement_speed", "step_height", "max_health", "knockback_resistance"],
    "aether_shield":     ["armor", "armor_toughness", "max_health", "knockback_resistance", "attack_damage"],
    "aether_accessory":  ["max_health", "luck", "attack_speed", "armor", "attack_damage"],
    "aether_gloves":     ["attack_damage", "attack_speed", "max_health", "luck", "armor"],
}

# alias -> registry id (short names keep the tables readable)
SHORT = {
    "max_health": "minecraft:generic.max_health",
    "attack_damage": "minecraft:generic.attack_damage",
    "armor": "minecraft:generic.armor",
    "armor_toughness": "minecraft:generic.armor_toughness",
    "knockback_resistance": "minecraft:generic.knockback_resistance",
    "movement_speed": "minecraft:generic.movement_speed",
    "attack_speed": "minecraft:generic.attack_speed",
    "luck": "minecraft:generic.luck",
    
    "attack_knockback": "minecraft:generic.attack_knockback",
    "spell_power": "irons_spellbooks:spell_power",
    "max_mana": "irons_spellbooks:max_mana",
    "step_height": "forge:step_height_addition",
    "swim_speed": "forge:swim_speed",
}

RARITIES = ["common", "uncommon", "rare", "epic", "mythic", "ancient"]
# (min factor, steps, step factor) relative to (base, step)
SCALE = {
    "common":   (1.0, 1, 1.0),
    "uncommon": (1.5, 2, 1.0),
    "rare":     (2.5, 4, 1.5),
    "epic":     (4.0, 5, 2.0),
    "mythic":   (6.0, 7, 2.5),
    "ancient":  (8.0, 10, 3.0),
}


def num(value):
    """Trims float noise: 2.5 -> 2.5, 0.30000000000000004 -> 0.3, 15.0 -> 15."""
    if abs(value - round(value)) < 1e-9:
        return int(round(value))
    text = ("%.4f" % value).rstrip("0").rstrip(".")
    return float(text)


def values_for(operation, base, step):
    values = {}
    for rarity in RARITIES:
        min_factor, steps, step_factor = SCALE[rarity]
        minimum = num(base * min_factor)
        increment = num(step * step_factor)
        if operation == "ADDITION" and base >= 1.0:
            # keep whole numbers where the unit is a whole point (health, armor, mana, ...)
            minimum = num(round(base * min_factor))
            increment = num(max(0.01, round(step * step_factor, 3)))
        values[rarity] = {"min": minimum, "steps": steps, "step": increment}
    return values


def write_json(path, payload):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, indent=4, ensure_ascii=False)
        handle.write("\n")


def main():
    if os.path.isdir(DATA_DIR):
        shutil.rmtree(DATA_DIR)

    lang_en, lang_zh = {}, {}
    written = 0
    for slot, aliases in SLOT_ATTRS.items():
        for alias in aliases:
            original = SHORT[alias]
            attribute = IRONS_SUBSTITUTE.get(original, original) if NO_IRONS else original
            # the variant takes the substituted attribute's own operation/values so the numbers stay sane
            operation, base, step, en_name, en_suffix, zh_name, zh_suffix = ATTRS[attribute]
            file_name = alias + "_plus"
            path = os.path.join(DATA_DIR, slot, "attribute", file_name + ".json")
            write_json(path, {
                "type": "apotheosis:attribute",
                "attribute": attribute,
                "operation": operation,
                "values": values_for(operation, base, step),
                "types": ["curios:" + slot],
            })
            affix_id = "%s:%s/attribute/%s" % (NS, slot, file_name)
            lang_en["affix." + affix_id] = en_name
            lang_en["affix." + affix_id + ".suffix"] = en_suffix
            lang_zh["affix." + affix_id] = zh_name
            lang_zh["affix." + affix_id + ".suffix"] = zh_suffix
            written += 1

    # UI strings for the curio tooltip block (see util/CurioTooltipLines)
    lang_en["apothiccuriosfix.tooltip.when_worn_as_curio"] = "When worn as a curio:"
    lang_zh["apothiccuriosfix.tooltip.when_worn_as_curio"] = "\u4f5c\u4e3a\u9970\u54c1\u4f69\u6234\u65f6\uff1a"

    if LANG_DIR:
        os.makedirs(LANG_DIR, exist_ok=True)
        for name, payload in (("en_us", lang_en), ("zh_cn", lang_zh)):
            write_json(os.path.join(LANG_DIR, name + ".json"), payload)

    print("namespace %s, slots: %d, affix files: %d, lang entries: %d -> %s"
          % (NS, len(SLOT_ATTRS), written, len(lang_en) // 2, DATA_DIR))


if __name__ == "__main__":
    main()
