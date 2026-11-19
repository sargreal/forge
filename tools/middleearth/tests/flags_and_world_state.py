#!/usr/bin/env python3
"""Regression test: flag conditions and flag-conditioned world state (story engine run 1).

Uses the hidden test origin (starts in Hobbiton) and test-only fixtures keyed on `test_*` flags that
normal play never sets (see docs/MiddleEarth/notes/story-engine-run1.md):
  - FlagCondition semantics through the console (`flag check`): unset = 0, `not`, ops, groups, map scope;
  - `spawnCondition` on a map object: forest_capital.tmx is patched for the test and restored afterwards;
  - shop reward `condition`: GreenItems (Hobbiton Provisions) sells Lembas only with character flag test_shop;
  - biome `enemyOverrides` in the Shire (add / replace / remove), checked in /state and by real spawns;
  - POI `questFlagsToDeactivate` and `condition` on Forest Capital.

Usage: tools/middleearth/tests/flags_and_world_state.py [--keep]  (own game unless one answers; DISPLAY_NUM)
"""
import json
import os
import sys
import time

from _harness import check, step, main, HERE

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
TMX = os.path.join(ROOT, "forge-gui/res/adventure/common/maps/map/main_story/forest_capital.tmx")
SHOP_ID = 63  # GreenItems in forest_capital.tmx
ANCHOR = '<property name="commonShopList" value="GreenItems"/>'
SPAWN_COND = '{"scope":"character","key":"test_spawn","op":"=","val":0}'


def con(g, cmd):
    return g.console(cmd).get("output", "").strip()


def flag_check(g, cond):
    return con(g, "flag check '%s'" % json.dumps(cond))


def enter_hobbiton(g):
    s = g.state()
    if s["location"].get("where") != "overworld":
        con(g, "leave")
        g.wait("onOverworld", 20000)
    con(g, "teleport to poi 'Forest Capital'")
    r = g.interact(poi="Forest Capital")
    check(r["reason"] == "sceneChanged", "could not enter Hobbiton: %s" % r)
    g.wait("inMap=forest_capital.tmx", 20000)


def shop_stock(g):
    shop = g.objects(id=SHOP_ID)
    check(shop, "shop %d missing" % SHOP_ID)
    g.teleport(shop[0]["x"], shop[0]["y"] - 10, unit="px")
    r = g.interact(SHOP_ID)
    check(r["reason"] == "sceneChanged", "shop did not open: %s" % r)
    s = g.state()
    check(s.get("reward") and s["reward"]["type"] == "Shop", "no shop screen: %s" % s.get("reward"))
    items = [i.get("name") for i in s["reward"]["items"]]
    g.accept_reward()
    return items


def biome_enemies(g):
    return g.state()["location"].get("biomeEnemies")


def poi_active(g, name="Forest Capital"):
    pois = [o for o in g.state()["objects"] if o["type"] == "poi" and o["name"] == name]
    check(pois, "POI %s not in /state objects" % name)
    return pois[0]["active"]


def run_patched(g):
    step("new game with origin Test (Hobbiton)")
    g.new_game(plane="MiddleEarth", name="Tester", difficulty="Normal", colors="G", mode="Constructed", origin="Test")
    g.wait("inMap=forest_capital.tmx", 30000)
    if g.dialog():  # a dialog left open by an earlier game in the same session
        g.close_dialog()

    # --- FlagCondition semantics (console `flag check`) ---
    check(flag_check(g, {"key": "unset_x", "op": "<", "val": 3}) == "true", "unset flag should read as 0")
    check(flag_check(g, {"key": "unset_x", "op": "<", "val": 3, "not": True}) == "false", "not on an unset flag")
    check(flag_check(g, {"key": "unset_x"}) == "false", "default op is != 0 (set)")
    check(flag_check(g, {"scope": "character", "key": "test_origin", "op": ">=", "val": 1}) == "true", "character scope")
    check(flag_check(g, {"any": [{"key": "unset_x"}, {"scope": "character", "key": "test_origin"}]}) == "true", "any group")
    check(flag_check(g, [{"key": "unset_x", "op": "=", "val": 0}, {"scope": "character", "key": "test_origin", "op": "!=", "val": 1}]) == "false", "array = all")
    check("unknown op" in flag_check(g, {"key": "x", "op": "~"}), "a malformed condition must be reported")
    con(g, "flag set map test_m 2")
    check(flag_check(g, {"scope": "map", "key": "test_m", "op": "=", "val": 2}) == "true", "map flag in its map")
    step("flag conditions ok (unset = 0, not, any/all, map scope)")

    # --- spawnCondition (test_spawn = 0 keeps the shop) + shop reward condition ---
    check(g.objects(id=SHOP_ID), "shop with spawnCondition test_spawn = 0 must spawn while the flag is unset")
    items = shop_stock(g)
    check("Lembas" not in items, "conditioned Lembas sold without test_shop: %s" % items)
    con(g, "flag set character test_shop 1")
    enter_hobbiton(g)  # stock is generated on first shop entry per map visit
    check(flag_check(g, {"scope": "map", "key": "test_m", "op": "=", "val": 2}) == "true", "map flag kept in its map")
    items = shop_stock(g)
    check("Lembas" in items, "conditioned Lembas missing with test_shop = 1: %s" % items)
    step("shop reward condition ok: %s" % items)

    con(g, "flag set character test_spawn 1")
    enter_hobbiton(g)
    check(not g.objects(id=SHOP_ID), "shop must not spawn with test_spawn = 1")
    con(g, "flag set character test_spawn 0")
    enter_hobbiton(g)
    check(g.objects(id=SHOP_ID), "shop must spawn again with test_spawn = 0")
    step("spawnCondition ok")

    # --- overworld: map flags read 0, biome enemyOverrides, POI deactivation ---
    con(g, "leave")
    g.wait("onOverworld", 20000)
    check(flag_check(g, {"scope": "map", "key": "test_m", "op": "=", "val": 0}) == "true", "map flags read 0 on the world map")
    base = biome_enemies(g)
    check(base and "Badger" not in base, "unexpected base Shire enemies: %s" % base)
    con(g, "flag set character test_enemy_override 1")
    check(biome_enemies(g) == base + ["Badger"], "add override: %s" % biome_enemies(g))
    con(g, "flag set character test_enemy_override 2")
    check(biome_enemies(g) == ["Wild Wolves", "Badger"], "replace override: %s" % biome_enemies(g))
    con(g, "flag set character test_enemy_override 3")
    check(biome_enemies(g) == ["Badger"], "remove override: %s" % biome_enemies(g))
    step("enemyOverrides: %s -> +Badger -> replace -> remove" % base)

    con(g, "god 300")  # enemies ignore the player while it walks to make spawns happen
    con(g, "remove enemy all")
    pos = g.state()["location"]["pos"]["tile"]
    x, y = pos["x"], pos["y"] - 12
    g.teleport(x, y)
    seen = []
    end = time.time() + 60
    leg = 0
    while time.time() < end and not seen:
        g.walk(x + (6 if leg % 2 == 0 else 0), y, timeout_ms=4000, pathfinding=False)
        leg += 1
        s = g.state()
        if s["location"].get("where") != "overworld":
            con(g, "leave")
            g.wait("onOverworld", 20000)
            g.teleport(x, y)
            continue
        seen = [o.get("enemy") for o in s["objects"] if o["type"] == "enemy"]
    check(seen, "no overworld spawn within 60 s")
    check(all(e == "Badger" for e in seen), "spawns ignore the override: %s" % seen)
    step("real spawns follow the override: %s" % seen)
    con(g, "flag set character test_enemy_override 0")
    check(biome_enemies(g) == base, "override cache not reset: %s" % biome_enemies(g))

    check(poi_active(g) is True, "Forest Capital should be active")
    con(g, "flag set quest test_hide_poi 1")
    check(poi_active(g) is False, "questFlagsToDeactivate did not hide Forest Capital")
    con(g, "flag set quest test_hide_poi 0")
    check(poi_active(g) is True, "Forest Capital should be back")
    con(g, "flag set character test_hide_poi_c 1")
    check(poi_active(g) is False, "POI condition did not hide Forest Capital")
    con(g, "flag set character test_hide_poi_c 0")
    check(poi_active(g) is True, "Forest Capital should be back (condition)")
    step("POI questFlagsToDeactivate and condition ok")


def run(g):
    original = open(TMX, encoding="utf-8").read()
    check(original.count(ANCHOR) == 1, "fixture anchor not found in forest_capital.tmx")
    patched = original.replace(ANCHOR, ANCHOR + '\n    <property name="spawnCondition" value="%s"/>'
                               % SPAWN_COND.replace('"', "&quot;"))
    try:
        open(TMX, "w", encoding="utf-8").write(patched)
        run_patched(g)
    finally:
        open(TMX, "w", encoding="utf-8").write(original)


if __name__ == "__main__":
    sys.exit(main(run))
