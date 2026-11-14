#!/usr/bin/env python3
"""Re-theme the generic (upstream) enemies that spawn in town/dungeon maps so their decks use only
the Middle-earth card pool. Edits MiddleEarth/world/enemies.json in place: sets `deck` (and optionally
`nameOverride`). Never touches `name`, `sprite`, `life`, `difficulty`, `spawnRate`, `rewards`.

Usage: python3 tools/middleearth/retheme-map-enemies.py [--dry-run]
Deck shorthand: "s:x" = decks/enemy/shadow/x.dck, "f:x" = decks/enemy/free/x.dck.
Table row:  "Enemy name": ("deck deck ...", "nameOverride" or None)
"""
import json, os, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PATH = os.path.join(ROOT, "forge-gui/res/adventure/MiddleEarth/world/enemies.json")

M = {
 # --- wizards / mages (difficulty tiers: Apprentice < Adept < Master) ---
 "Apprentice Black Wizard": ("s:snaga s:dol_guldur_spirit", "Dol Guldur Acolyte"),
 "Apprentice Blue Wizard": ("f:hobbit_troublemakers f:dead_marshes", "Marsh-lights Trickster"),
 "Apprentice Green Wizard": ("f:wild_wolves f:beorning", "Wildwood Hedge-witch"),
 "Apprentice Red Wizard": ("s:misty_goblin", "Goblin Fire-eater"),
 "Apprentice White Wizard": ("f:gondor_guard", "Gondorian Healer"),
 "Adept Black Wizard": ("s:dol_guldur_spirit s:dol_guldur_necromancer", "Dol Guldur Adept"),
 "Adept Blue Wizard": ("s:easterling s:crebain", "Easterling Sorcerer"),
 "Adept Green Wizard": ("f:huorns f:wild_bears", "Wild-wood Druid"),
 "Adept Red Wizard": ("s:uruk_hai s:orc_captain", "Isengard Fire-wright"),
 "Adept White Wizard": ("f:rohirrim_riders f:gondor_captain", "Gondorian Loremaster"),
 "Master Black Wizard": ("s:dol_guldur_necromancer s:ringwraith", "Master of Dol Guldur"),
 "Master Blue Wizard": ("s:easterling s:haradrim", "Eastern Sorcerer-king"),
 "Master Green Wizard": ("f:ents f:huorns", "Elder of the Wild-wood"),
 "Master Red Wizard": ("s:uruk_captain s:olog_hai", "Orthanc Fire-master"),
 "Master White Wizard": ("f:gondor_captain f:rivendell_lord", "Loremaster of Minas Tirith"),
 "Corrupted Blue Wizard": ("s:the_necromancer s:crebain", "Corrupted Sorcerer"),
 "Corrupted Red Wizard": ("s:uruk_captain s:warg_rider", "Corrupted Fire-wright"),
 "Wild-Magic Sorcerer": ("s:easterling s:haradrim", "Eastern Sorcerer"),
 "Pyromancer": ("s:mordor_orc s:uruk_berserker", "Mordor Fire-caller"),
 "Runelord": ("f:erebor_dwarves f:iron_hills", "Dwarven Runelord"),
 "Illusionist": ("f:hobbit_troublemakers f:dead_marshes", "Dead Marshes Illusionist"),
 "Simic Wizard": ("f:dunlendings f:wild_wolves", "Dunlending Hedge-wizard"),
 "Loamspeaker": ("f:huorns f:ents", "Loamspeaker of the Wood"),
 "Mindclaw Shaman": ("s:crebain s:dol_guldur_spirit", "Mindclaw Shaman"),
 "Shaman of Zedruu": ("s:haradrim s:easterling", "Harad Shaman"),
 "Disciple of Teferi": ("f:rivendell_lord f:lorien_wardens", "Disciple of Rivendell"),
 "Brain Mole": ("f:dead_marshes", "Marsh-mole"),
 "Cephalid": ("s:easterling s:haradrim", "Easterling Mind-binder"),
 "Aether Channeler": ("s:easterling s:crebain", "Easterling Channeler"),
 "Dark Enchanter": ("s:the_necromancer s:dol_guldur_necromancer", "Dark Enchanter of Dol Guldur"),
 "Witch": ("f:dunlendings f:bill_ferny", None),
 "Dawnhart Witch": ("f:dunlendings f:wild_wolves", "Dunlending Wise-woman"),
 "Orzhov Cleric": ("s:easterling s:dol_guldur_spirit", "Easterling Priest"),
 "Curselord": ("s:dol_guldur_necromancer s:ringwraith", "Curse-lord of Angmar"),
 "False Monk": ("f:dunlendings f:bill_ferny", "False Pilgrim"),
 "High Cultist": ("s:dol_guldur_necromancer s:easterling s:ringwraith", "High Cultist of the Eye"),
 "Doppelganger": ("f:gollum f:hobbit_troublemakers", "Shape-changer"),
 "Mimic": ("f:gollum f:dead_marshes", "Marsh Mimic"),

 # --- undead ---
 "Zombie": ("f:barrow_wights", "Barrow-wight Thrall"),
 "Zombie Lord": ("f:barrow_wights f:barrow_lord", "Barrow-wight Captain"),
 "Zombie Master": ("f:barrow_lord s:dol_guldur_necromancer", "Barrow-lord's Servant"),
 "Greater Zombie": ("f:barrow_wights f:barrow_lord", "Barrow-wight Revenant"),
 "Skeleton Soldier": ("f:barrow_wights f:dead_marshes", "Barrow-wight Guard"),
 "Reassembling Skeleton": ("f:barrow_wights f:dead_marshes", "Restless Barrow-wight"),
 "Ghoul": ("f:barrow_wights s:dol_guldur_spirit", "Grave-ghoul"),
 "Ghoul Gravecaller": ("f:barrow_lord s:dol_guldur_necromancer", "Grave-caller"),
 "Necrogoyf": ("f:dead_marshes f:barrow_wights", "Marsh-corpse"),
 "Mummy": ("f:barrow_wights s:easterling", "Entombed King's Wight"),
 "Pharaoh": ("f:barrow_lord s:easterling", "Wight-king of the East"),
 "Geist": ("f:dead_marshes s:dol_guldur_spirit", "Dead Marsh Spirit"),
 "Wraith": ("s:dol_guldur_spirit f:dead_marshes", "Dol Guldur Wraith"),
 "Shade": ("s:dol_guldur_spirit s:ringwraith", "Fell Shade"),
 "Lich": ("s:dol_guldur_necromancer s:the_necromancer", "Barrow-lich"),
 "Necromancer": ("s:dol_guldur_necromancer", "Necromancer Adept"),
 "Vampire": ("s:crebain s:dol_guldur_spirit", "Blood-drinker Bat-lord"),
 "Vampire Lord": ("s:black_rider s:dol_guldur_spirit", "Vampire Lord of the Mountains"),
 "Ancient Vampire": ("s:ringwraith s:dol_guldur_necromancer", "Ancient Blood-lord"),
 "Death Knight": ("s:black_rider f:barrow_lord", "Dead Knight of Angmar"),
 "Dark Knight": ("s:black_rider s:uruk_captain", "Black Knight of Mordor"),
 "False Knight": ("f:barrow_wights f:barrow_lord", "Hollow Knight"),
 "Unholy Knight": ("s:ringwraith f:barrow_lord", "Unholy Knight of Angmar"),
 "Bone Dragon": ("s:fell_beast f:barrow_lord", "Bone Drake"),
 "Grave Titan": ("s:cave_troll f:barrow_lord", "Grave-troll"),
 "Fallen Angel": ("s:winged_nazgul s:ringwraith", "Fallen Messenger"),
 "Walking Brain": ("f:dead_marshes", "Wandering Marsh-light"),
 "Plague Rat Swarm": ("s:snaga s:misty_goblin", "Plague Rats of Moria"),

 # --- goblins / orcs / kobolds ---
 "Goblin": ("s:misty_goblin", None),
 "Goblin Chief": ("s:moria_orc s:great_goblin", "Goblin Chieftain"),
 "Goblin Warrior": ("s:misty_goblin s:warg_rider", None),
 "Undead Goblin": ("s:moria_orc f:barrow_wights", "Barrow Goblin"),
 "Orc Brute": ("s:uruk_berserker", None),
 "Orc Hunter": ("s:mordor_orc s:warg_rider", None),
 "Orc Warrior": ("s:uruk_hai s:mordor_orc", None),
 "Scourgemaster": ("s:orc_captain s:uruk_captain", "Orc Taskmaster"),
 "Boggart": ("s:snaga", "Snaga Skulker"),
 "Kobold Dragoncaller": ("s:snaga s:fell_beast", "Snaga Drake-caller"),
 "Kobold Shaman": ("s:snaga s:misty_goblin", "Snaga Shaman"),
 "Satyr": ("s:snaga", "Snaga Scout"),
 "Khenra Warrior": ("f:southern_ruffians s:haradrim", "Harad Desert Warrior"),
 "Viashino": ("s:snaga s:mordor_orc", "Orc Lizard-rider"),
 "Hellion": ("s:mordor_orc s:uruk_berserker", "Hill-orc Marauder"),
 "Spiked Ravager": ("s:uruk_berserker s:orc_captain", "Spiked Uruk Ravager"),

 # --- soldiers, knights, guards ---
 "Armored Knight": ("f:gondor_guard f:gondor_captain", "Gondorian Knight"),
 "Knight Mercenary": ("f:gondor_guard f:rohirrim_riders", "Sellsword Knight"),
 "Aerie Guard": ("f:gondor_guard f:great_eagles", "Guard of the Eyrie"),
 "Kor Warrior": ("f:rohirrim_riders f:gondor_guard", "Rohan Spearman"),
 "Centaur Warrior": ("f:rohirrim_riders", "Horse-lord Warrior"),
 "Gladiator": ("f:rohan_marshal f:gondor_captain", "Pit Champion"),
 "Cathar": ("f:gondor_guard f:rohirrim_riders", None),  # keeps "Human Raider"
 "Khan": ("f:dunlendings f:southern_ruffians", "Raider Chief"),
 "Human Mercenary": ("f:southern_ruffians f:gondor_guard", "Sellsword"),
 "Adventurer": ("f:ranger_north f:hobbit_troublemakers", None),
 "Archaeologist": ("f:dwarf_prospectors f:hobbit_troublemakers", "Delver of Ruins"),
 "Archer": ("f:ranger_ithilien f:gondor_guard", None),
 "Archer Elite": ("f:ranger_ithilien f:ranger_north", "Elite Ranger-archer"),
 "Horseman": ("f:rohirrim_riders", None),
 "Beastmaster": ("f:wild_wolves f:wild_bears", None),
 "Barbarian": ("f:dunlendings f:wild_wolves", None),
 "Eldraine Knight": ("f:gondor_guard f:rohirrim_riders", None),
 "Eldraine Faerie": ("f:lorien_wardens", None),

 # --- bandits / thieves ---
 "Bandit Archer": ("f:southern_ruffians f:ranger_ithilien", "Ruffian Archer"),
 "Bandit Leader": ("f:bill_ferny f:sharkeys_men", "Ruffian Chief"),
 "Bandit Scoundrel": ("f:southern_ruffians", "Ruffian Scoundrel"),
 "Bandit Slingshot": ("f:southern_ruffians f:hobbit_troublemakers", "Ruffian Slinger"),
 "Bandit Trapmaster": ("f:sharkeys_men f:southern_ruffians", "Ruffian Trapper"),

 # --- pirates / sea ---
 "Pirate": ("s:corsair", "Corsair"),
 "Pirate Captain": ("s:corsair_captain", "Corsair Captain of Umbar"),
 "Merfolk Fighter": ("s:corsair f:dead_marshes", "Umbar Sea-raider"),
 "Merfolk Lord": ("s:corsair_captain f:watcher", "Umbar Sea-lord"),
 "Merfolk Soldier": ("s:corsair", "Umbar Deckhand"),
 "Water Elemental": ("f:watcher f:dead_marshes", "Water-spirit"),
 "Crocodile": ("f:dead_marshes f:watcher", "Marsh Crocodile"),

 # --- elves & fae ---
 # (Eldraine Faerie above)

 # --- dwarves ---
 "Axgard Dwarf": ("f:iron_hills", None),
 "Dwarf Demolisher": ("f:erebor_dwarves f:dwarf_prospectors", "Dwarf Sapper"),
 "Dwarf Mercenary": ("f:iron_hills f:dwarf_prospectors", "Iron Hills Mercenary"),
 "Dwarf Pilot": ("f:dwarf_prospectors f:erebor_dwarves", "Dwarf Engineer"),
 "White Dwarf": ("f:erebor_dwarves", "Dwarf of the White Hills"),

 # --- dragons ---
 "Greater Sandwurm": ("f:cold_drakes", "Desert Cold-drake"),
 "Fire Giant": ("s:hill_troll s:cave_troll", "Fire-scarred Hill-troll"),
 "Efreet": ("s:fell_beast s:mordor_orc", "Fire-drake of Mordor"),
 "Hellhound": ("f:warg_pack s:warg_rider", "Hell-warg"),

 # --- spiders & insects ---
 "Cave Spider": ("f:mirkwood_spiders s:shelob_brood", None),
 "Giant Spider": ("f:mirkwood_spiders f:great_spiders", None),
 "Dragonfly": ("f:mirkwood_spiders", "Giant Mirkwood Fly"),
 "Giant Fly": ("f:mirkwood_spiders", "Giant Marsh Fly"),
 "Scarab": ("s:shelob_brood", "Carrion Beetle"),
 "Scorpion": ("s:shelob_brood s:haradrim", "Giant Scorpion of Harad"),

 # --- beasts ---
 "Wolf": ("f:wild_wolves f:warg_pack", None),
 "Werewolf": ("f:warg_pack f:wild_wolves", "Were-warg"),
 "Badger": ("f:wild_bears", None),
 "Bat": ("s:crebain", "Great Bat"),
 "Caracal": ("f:wild_wolves", "Wildcat"),
 "Elk": ("f:beorning f:wild_bears", "Wild Stag"),
 "Falcon": ("f:great_eagles", "Eagle's Fledgling"),
 "Hippo": ("s:mumak", None),
 "Lion": ("f:wild_wolves f:beorning", "Mountain Lion"),
 "Owl": ("f:great_eagles s:crebain", "Great Owl"),
 "Parrot": ("s:crebain", "Southern Parrot"),
 "Poisonous Snake": ("s:shelob_brood f:dead_marshes", "Poisonous Marsh-serpent"),
 "Viper": ("s:shelob_brood", "Harad Viper"),
 "Polar Bear": ("f:wild_bears f:beorning", "Cold-land Bear"),
 "Raven": ("s:crebain", "Carrion Raven"),
 "Rhino": ("s:mumak", "War-beast of Harad"),
 "Vulture": ("s:crebain", "Carrion Vulture"),
 "Wild Rat": ("s:snaga s:misty_goblin", "Wild Cave-rat"),
 "Yeti": ("f:wild_bears f:cold_drakes", "Snow-troll"),
 "Harpy": ("s:crebain", "Crebain Scavenger"),
 "Harpy 2": ("s:crebain s:fell_beast", None),
 "Gorgon": ("s:stone_troll", "Stone-turned Troll"),
 "Gorgon 2": ("s:stone_troll s:hill_troll", None),
 "Minotaur Flayer": ("s:uruk_berserker s:hill_troll", "Uruk Flayer"),
 "Eye": ("f:watcher", "Watching Eye"),
 "Beholder": ("f:watcher s:the_necromancer", "Eye of the Deep"),
 "Cloaker": ("s:dol_guldur_spirit", "Cloaked Shadow"),
 "Evil Wall": ("f:huorns s:stone_troll", "Haunted Wall"),

 # --- giants / trolls / ogres ---
 "Cyclops": ("s:hill_troll", None),
 "Hulking Brute": ("s:cave_troll", None),
 "Mountain Ogre": ("s:hill_troll s:cave_troll", "Mountain Troll"),
 "Swamp Troll": ("s:olog_hai s:stone_troll", None),
 "Troll": ("s:stone_troll", None),
 "Forest Giant": ("f:ents f:huorns", "Forest Ent-giant"),
 "Frost Titan": ("s:olog_hai f:cold_drakes", "Frost Troll-chief"),
 "Eldrazi": ("s:olog_hai s:the_necromancer", "Fell Horror"),
 "Eldrazi Devastator": ("s:olog_hai s:uruk_captain", "Fell Devastator"),
 "Flesh Abomination": ("s:olog_hai s:mouth_of_sauron", None),

 # --- demons / devils / horrors ---
 "Ashmouth Devil": ("s:uruk_berserker s:orc_captain", None),
 "Demon": ("s:uruk_captain s:olog_hai", "Fell Spirit"),
 "Cosmic Horror": ("s:mouth_of_sauron", None),
 "Tarkir Djinn": ("s:easterling s:haradrim", "Desert Spirit"),

 # --- elementals / constructs / plants ---
 "Ice Elemental": ("f:cold_drakes", "Frost-wraith"),
 "Black Golem": ("s:stone_troll s:cave_troll", "Black Stone-troll"),
 "Golgari Treefolk": ("f:huorns f:old_man_willow", "Rotting Huorn"),
 "Treefolk Guardian": ("f:huorns f:ents", None),
}

# Existing nameOverride values that no longer fit are replaced here only when listed in M (value not None).
# Entries mapped to None keep whatever nameOverride they already have.

# challenger enemies (ordinary map enemies): strong human/Mordor mini-bosses
M.update({
 "Challenger 20": ("s:uruk_captain f:gondor_captain", None),
 "Challenger 21": ("f:rohan_marshal f:rivendell_lord", None),
 "Challenger 22": ("s:ringwraith s:black_rider", None),
 # bosses
 "Emrakul": ("s:balrog", None),
 "Griselbrand": ("s:witch_king", None),
})

def path(tok):
    kind, name = tok.split(":")
    return "decks/enemy/%s/%s.dck" % ({"s": "shadow", "f": "free"}[kind], name)

def main():
    dry = "--dry-run" in sys.argv
    raw = open(PATH, encoding="utf-8").read()
    data = json.loads(raw)
    by = {e["name"]: e for e in data}
    changed = renamed = 0
    for name, (decks, nm) in M.items():
        e = by.get(name)
        if e is None:
            print("MISSING enemy", name); continue
        new = [path(t) for t in decks.split()]
        for p in new:
            if not os.path.exists(os.path.join(ROOT, "forge-gui/res/adventure/MiddleEarth", p)):
                sys.exit("no such deck " + p)
        if e["deck"] != new:
            e["deck"] = new; changed += 1
        if nm is not None and e.get("nameOverride") != nm:
            e["nameOverride"] = nm; renamed += 1
    shown = {}
    for e in data:
        shown.setdefault(e.get("nameOverride") or e["name"], []).append(e["name"])
    for k, v in shown.items():
        if len(v) > 1 and any(n in M for n in v):
            print("DUPLICATE display name", k, v)
    print("re-themed %d, renamed %d, table rows %d" % (changed, renamed, len(M)))
    if not dry:
        with open(PATH, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")

if __name__ == "__main__":
    main()
