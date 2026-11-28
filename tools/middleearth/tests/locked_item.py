#!/usr/bin/env python3
"""Regression test: equip-locked items (ItemData `locked` / `lockCondition`, story engine run 2).

Fixture item "Test Cursed Band" (Neck, locked while character flag test_lock >= 1), through the dev API's
/inventory (the Inventory's equip toggle and delete button):
1. Without test_lock: it equips, unequips and drops like any item.
2. With test_lock: given, it puts itself on (auto-equip). Unequip, sell, drop and equipping another Neck item over it
   are refused; `crack` skips it.
3. test_lock cleared: it unequips and drops again.
Also: The One Ring is locked in the plane data.

Usage: tools/middleearth/tests/locked_item.py [--keep]  (own game unless one answers; DISPLAY_NUM)
"""
import sys

from _harness import check, step, main

BAND = "Test Cursed Band"


def inv(g, action="list", item=None):
    body = {"action": action}
    if item:
        body["item"] = item
    return g.call("/inventory", body)


def item(r, name=BAND):
    found = [i for i in r["items"] if i["name"] == name]
    return found[-1] if found else None


def run(g):
    step("new game with origin Test")
    g.new_game(plane="MiddleEarth", name="Tester", difficulty="Normal", colors="G", mode="Constructed", origin="Test")
    g.wait("inMap=forest_capital.tmx", 30000)
    if g.dialog():
        g.close_dialog()

    g.console("give item '%s'" % BAND)
    r = inv(g)
    b = item(r)
    check(b and not b["locked"] and not b["equipped"], "unlocked band, not auto-equipped: %s" % b)
    check(inv(g, "equip", BAND)["done"] and inv(g, "unequip", BAND)["done"], "equip/unequip without the lock")
    check(inv(g, "drop", BAND)["done"] and item(inv(g)) is None, "drop without the lock")
    step("without test_lock: equip, unequip and drop work")

    g.console("flag set character test_lock 1")
    g.console("give item '%s'" % BAND)
    b = item(inv(g))
    check(b and b["locked"] and b["equipped"], "a locked item puts itself on: %s" % b)
    check(not inv(g, "unequip", BAND)["done"], "unequip must be refused")
    check(not inv(g, "drop", BAND)["done"], "drop must be refused")
    check(not inv(g, "sell", BAND)["done"], "sell must be refused")
    b = item(inv(g))
    check(b and b["equipped"], "still equipped: %s" % b)
    g.console("give item 'Life Amulet'")
    other = [i for i in inv(g)["items"] if i["slot"] == "Neck" and i["name"] != BAND]
    check(other, "Life Amulet not given")
    check(not inv(g, "equip", other[0]["name"])["done"], "equipping %s over the locked band must be refused" % other[0]["name"])
    check(item(inv(g))["equipped"], "band replaced in its slot")
    g.console("crack")  # cracking unequips a random item: never a locked one
    check(item(inv(g))["equipped"], "a locked item must not crack off")
    step("with test_lock: unequip, drop, sell and replacing it by %s refused" % other[0]["name"])

    g.console("flag set character test_lock 0")
    check(not item(inv(g))["locked"], "lock follows the condition")
    check(inv(g, "unequip", BAND)["done"] and inv(g, "drop", BAND)["done"], "unlocked again: unequip and drop")
    step("test_lock cleared: unequip and drop work again")

    g.console("give item 'The One Ring'")
    ring = item(inv(g), "The One Ring")
    check(ring and ring["locked"] and ring["equipped"], "The One Ring is locked and puts itself on: %s" % ring)
    step("The One Ring: locked, auto-equipped")


if __name__ == "__main__":
    sys.exit(main(run))
