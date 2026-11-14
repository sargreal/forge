#!/usr/bin/env python3
"""Render the Middle-earth biome map (one pixel per world tile) from region polygons, rivers and lakes.

    python3 tools/middleearth/biomemap/make_biome_map.py            # write PNG + preview + plane data
    python3 tools/middleearth/biomemap/make_biome_map.py --no-data  # PNG + preview only

Outputs
  forge-gui/res/adventure/MiddleEarth/world/biome_map.png   the map the engine reads (world.json "biomeMap")
  .me-test/biome_map_preview.png                            labelled preview for review (2x)
and, unless --no-data, updates the plane data so it matches the map:
  world/biomes/*.json        "color" (BIOME_COLORS) and startPointX/Y, width, height = the biome's bounding box
                             (the engine still anchors structures and POIs on that box)
  world/points_of_interest.json  offsetX/offsetY/radiusFactor of every POI in POI_ANCHORS
  world/world.json           "biomeMap", playerStartPosX/Y (from the Spawn anchor)

Coordinates: fractions of the world, x = 0 west .. 1 east, y = 0 NORTH .. 1 south (image rows).
Layers are painted in this order, later wins: LANDS, MOUNTAINS, PASSES (restore land), SEAS, ISLANDS,
LAKES, RIVERS, FORDS (restore what was under the water). Region and coast edges get a small smooth
domain warp so borders look natural; rivers get a small deterministic meander. Everything is seeded,
so the output is reproducible.
"""
import argparse
import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
PLANE = os.path.join(ROOT, "forge-gui/res/adventure/MiddleEarth")
OUT_PNG = os.path.join(PLANE, "world/biome_map.png")
PREVIEW = os.path.join(ROOT, ".me-test/biome_map_preview.png")
W = H = 900  # world size in tiles (world.json width/height)

# --------------------------------------------------------------------------------------------
# Biome colours: must be unique, the engine maps pixel colour -> biome by exact RGB match.
# --------------------------------------------------------------------------------------------
BIOME_COLORS = {
    "ocean": "0054a2",            # base.json, also rivers and lakes (Base water tiles, collision)
    "northern_waste": "10a2e0",
    "eriador": "59a650",
    "wilderland": "4e9945",
    "anduin_vale": "66b35c",
    "brown_lands": "aeaeae",
    "rhun": "efe697",
    "minhiriath": "7bb663",
    "enedwaith": "9a9a9a",
    "harad_waith": "e6d98a",
    "south_gondor": "f5eeb0",
    "umbar": "d9cc7a",
    "gondor": "3f8f3a",
    "belfalas": "70c070",
    "tolfalas": "85cc7a",
    "rohan": "a3c25a",
    "mordor": "110903",
    "fangorn": "2f7a2a",
    "lothlorien": "c8d84a",
    "mirkwood": "1f2a14",
    "mirkwood_south": "2a3320",
    "rivendell": "8fd18a",
    "shire": "9ccf5a",
    "harlindon": "5fb87a",
    "forlindon": "4fae70",
    "ered_luin": "b63729",
    "ered_luin_south": "c04535",
    "ered_mithrin": "a83020",
    "ered_mithrin_east": "9c2a1c",
    "misty_mountains_north": "c2503a",
    "misty_mountains": "b8412f",
    "misty_mountains_moria": "ad3a2a",
    "misty_mountains_south": "c85a40",
    "erebor": "d06030",
    "iron_hills": "a0402a",
    "ered_nimrais_west": "b84a3a",
    "ered_nimrais": "c05a48",
    "ered_nimrais_east": "a84838",
    "ered_lithui": "7a2018",
    "ered_lithui_east": "6e1c14",
    "ephel_duath_1": "8a2a1c",
    "ephel_duath_mid": "902e20",
    "ephel_duath_2": "822618",
    "dead_marshes": "3a4a2a",
}

# --------------------------------------------------------------------------------------------
# Land regions (painted in this order, later wins). Broad lands first, then the regions on top.
# --------------------------------------------------------------------------------------------
NORTHERN_WASTE = [(0, 0), (1, 0), (1, 0.07), (0.9, 0.075), (0.8, 0.06), (0.7, 0.052), (0.6, 0.055), (0.52, 0.06),
                  (0.45, 0.08), (0.38, 0.075), (0.3, 0.08), (0.2, 0.075), (0.12, 0.065), (0.05, 0.06), (0, 0.06)]
ERIADOR = [(0.0, 0.05), (0.5, 0.05), (0.5, 0.3), (0.53, 0.47), (0.42, 0.47), (0.3, 0.45), (0.15, 0.5), (0.0, 0.5)]
WILDERLAND = [(0.5, 0.05), (1, 0.05), (1, 0.62), (0.72, 0.62), (0.6, 0.56), (0.5, 0.47)]
ANDUIN_VALE = [(0.525, 0.09), (0.6, 0.095), (0.612, 0.2), (0.602, 0.29), (0.598, 0.36), (0.594, 0.43), (0.585, 0.47),
               (0.56, 0.465), (0.545, 0.4), (0.528, 0.3), (0.52, 0.18)]
BROWN_LANDS = [(0.585, 0.44), (0.66, 0.445), (0.72, 0.47), (0.76, 0.52), (0.785, 0.572), (0.73, 0.578),
               (0.68, 0.57), (0.63, 0.56), (0.608, 0.545), (0.592, 0.5)]
RHUN = [(0.85, 0.3), (1, 0.28), (1, 0.58), (0.86, 0.575), (0.84, 0.5), (0.85, 0.4)]
MINHIRIATH = [(0.262, 0.41), (0.33, 0.4), (0.412, 0.42), (0.37, 0.48), (0.32, 0.55), (0.29, 0.615), (0.25, 0.58),
              (0.2, 0.54), (0.165, 0.505), (0.21, 0.47), (0.245, 0.44)]
ENEDWAITH = [(0.412, 0.42), (0.47, 0.44), (0.49, 0.5), (0.49, 0.56), (0.5, 0.62), (0.5, 0.66), (0.45, 0.68),
             (0.4, 0.71), (0.36, 0.69), (0.3, 0.63), (0.29, 0.615), (0.32, 0.55), (0.37, 0.48)]
HARAD = [(0.36, 1.0), (0.4, 0.92), (0.45, 0.85), (0.6, 0.83), (0.75, 0.81), (1, 0.8), (1, 1.0)]
SOUTH_GONDOR = [(0.615, 0.795), (0.7, 0.8), (0.8, 0.825), (0.8, 0.88), (0.72, 0.9), (0.63, 0.89), (0.6, 0.87), (0.62, 0.83)]
UMBAR = [(0.38, 0.87), (0.47, 0.86), (0.57, 0.865), (0.6, 0.89), (0.59, 0.94), (0.52, 0.965), (0.45, 0.96), (0.38, 0.95)]
GONDOR = [(0.57, 0.665), (0.6, 0.625), (0.63, 0.6), (0.66, 0.585), (0.69, 0.575), (0.725, 0.585), (0.725, 0.8),
          (0.62, 0.8), (0.575, 0.785), (0.56, 0.72)]
BELFALAS = [(0.38, 0.69), (0.47, 0.655), (0.53, 0.66), (0.575, 0.67), (0.585, 0.73), (0.575, 0.79), (0.52, 0.81),
            (0.45, 0.79), (0.37, 0.77)]
ROHAN = [(0.48, 0.535), (0.52, 0.5), (0.56, 0.47), (0.585, 0.47), (0.605, 0.545), (0.63, 0.565), (0.66, 0.585),
         (0.63, 0.6), (0.6, 0.63), (0.57, 0.65), (0.53, 0.645), (0.49, 0.64), (0.47, 0.57)]
MORDOR = [(0.735, 0.588), (1.0, 0.588), (1.0, 0.81), (0.735, 0.8)]
FANGORN = [(0.495, 0.465), (0.53, 0.455), (0.56, 0.47), (0.565, 0.5), (0.55, 0.53), (0.52, 0.535), (0.495, 0.52),
           (0.488, 0.49)]
LOTHLORIEN = [(0.525, 0.412), (0.555, 0.403), (0.575, 0.418), (0.58, 0.45), (0.56, 0.466), (0.535, 0.457), (0.524, 0.44)]
MIRKWOOD = [(0.6, 0.1), (0.66, 0.095), (0.72, 0.105), (0.742, 0.16), (0.738, 0.22), (0.728, 0.29), (0.605, 0.29),
            (0.612, 0.2)]
MIRKWOOD_SOUTH = [(0.605, 0.29), (0.728, 0.29), (0.718, 0.33), (0.7, 0.37), (0.68, 0.43), (0.64, 0.445),
                  (0.615, 0.44), (0.602, 0.4), (0.6, 0.33)]
RIVENDELL = [(0.452, 0.268), (0.478, 0.262), (0.49, 0.285), (0.482, 0.299), (0.456, 0.298)]
SHIRE = [(0.155, 0.3), (0.18, 0.28), (0.22, 0.27), (0.255, 0.28), (0.268, 0.3), (0.272, 0.33), (0.284, 0.332),
         (0.286, 0.36), (0.275, 0.375), (0.268, 0.4), (0.262, 0.415), (0.24, 0.405), (0.2, 0.39), (0.17, 0.37),
         (0.155, 0.34)]  # includes Buckland, east of the Brandywine
HARLINDON = [(0.0, 0.38), (0.07, 0.37), (0.118, 0.36), (0.128, 0.38), (0.135, 0.44), (0.145, 0.49), (0.1, 0.5),
             (0.0, 0.5)]
FORLINDON = [(0.0, 0.06), (0.09, 0.065), (0.1, 0.1), (0.11, 0.2), (0.115, 0.3), (0.112, 0.352), (0.08, 0.35),
             (0.0, 0.35)]
DEAD_MARSHES = [(0.645, 0.543), (0.69, 0.545), (0.712, 0.563), (0.69, 0.58), (0.655, 0.577)]
TOLFALAS = ("ellipse", 0.592, 0.826, 0.015, 0.013)

LANDS = [
    ("northern_waste", NORTHERN_WASTE), ("eriador", ERIADOR), ("wilderland", WILDERLAND), ("harad_waith", HARAD),
    ("anduin_vale", ANDUIN_VALE), ("brown_lands", BROWN_LANDS), ("rhun", RHUN), ("minhiriath", MINHIRIATH),
    ("enedwaith", ENEDWAITH), ("south_gondor", SOUTH_GONDOR), ("umbar", UMBAR), ("mordor", MORDOR),
    ("gondor", GONDOR), ("belfalas", BELFALAS), ("rohan", ROHAN), ("fangorn", FANGORN), ("lothlorien", LOTHLORIEN),
    ("mirkwood", MIRKWOOD), ("mirkwood_south", MIRKWOOD_SOUTH), ("rivendell", RIVENDELL), ("shire", SHIRE),
    ("harlindon", HARLINDON), ("forlindon", FORLINDON), ("dead_marshes", DEAD_MARSHES),
]

# --------------------------------------------------------------------------------------------
# Mountains (painted over the lands)
# --------------------------------------------------------------------------------------------
ERED_LUIN = [(0.085, 0.1), (0.11, 0.095), (0.125, 0.15), (0.128, 0.25), (0.122, 0.33), (0.11, 0.345), (0.098, 0.3),
             (0.09, 0.2)]
ERED_LUIN_SOUTH = [(0.12, 0.372), (0.135, 0.367), (0.15, 0.4), (0.16, 0.46), (0.152, 0.495), (0.137, 0.49), (0.13, 0.43)]
ERED_MITHRIN = [(0.53, 0.062), (0.6, 0.055), (0.652, 0.06), (0.655, 0.09), (0.6, 0.097), (0.54, 0.1)]
ERED_MITHRIN_EAST = [(0.652, 0.06), (0.72, 0.054), (0.762, 0.064), (0.756, 0.09), (0.7, 0.096), (0.655, 0.09)]
MISTY_NORTH = [(0.505, 0.045), (0.53, 0.05), (0.536, 0.1), (0.53, 0.17), (0.503, 0.17), (0.49, 0.1), (0.495, 0.06)]
MISTY = [(0.49, 0.17), (0.53, 0.17), (0.526, 0.24), (0.52, 0.3), (0.495, 0.3), (0.485, 0.24)]
MISTY_MORIA = [(0.495, 0.3), (0.52, 0.3), (0.516, 0.36), (0.511, 0.41), (0.485, 0.41), (0.481, 0.36)]
MISTY_SOUTH = [(0.485, 0.41), (0.511, 0.41), (0.506, 0.46), (0.496, 0.51), (0.486, 0.535), (0.475, 0.5), (0.476, 0.45)]
EREBOR = ("ellipse", 0.778, 0.125, 0.02, 0.02)
IRON_HILLS = [(0.86, 0.11), (0.92, 0.1), (0.96, 0.12), (0.95, 0.16), (0.9, 0.165), (0.865, 0.15)]
ERED_NIMRAIS_WEST = [(0.375, 0.745), (0.42, 0.7), (0.45, 0.665), (0.49, 0.643), (0.5, 0.665), (0.47, 0.69),
                     (0.44, 0.72), (0.4, 0.755)]  # ends in Cape Andrast
ERED_NIMRAIS = [(0.49, 0.637), (0.53, 0.627), (0.58, 0.635), (0.585, 0.66), (0.54, 0.667), (0.5, 0.665)]
ERED_NIMRAIS_EAST = [(0.58, 0.635), (0.62, 0.642), (0.645, 0.652), (0.648, 0.666), (0.63, 0.677), (0.6, 0.675),
                     (0.585, 0.66)]  # ends in Mindolluin, behind Minas Tirith
ERED_LITHUI = [(0.725, 0.573), (0.86, 0.568), (0.86, 0.605), (0.8, 0.61), (0.745, 0.605), (0.722, 0.595)]
ERED_LITHUI_EAST = [(0.86, 0.568), (1.0, 0.572), (1.0, 0.602), (0.92, 0.61), (0.86, 0.605)]
EPHEL_DUATH_1 = [(0.716, 0.595), (0.746, 0.6), (0.746, 0.7), (0.719, 0.7)]   # Morgul Vale
EPHEL_DUATH_MID = [(0.719, 0.7), (0.746, 0.7), (0.752, 0.79), (0.726, 0.8)]
EPHEL_DUATH_2 = [(0.726, 0.79), (0.8, 0.8), (0.9, 0.805), (1.0, 0.81), (1.0, 0.84), (0.9, 0.835), (0.8, 0.83),
                 (0.73, 0.826)]

MOUNTAINS = [
    ("ered_luin", ERED_LUIN), ("ered_luin_south", ERED_LUIN_SOUTH), ("ered_mithrin", ERED_MITHRIN),
    ("ered_mithrin_east", ERED_MITHRIN_EAST), ("misty_mountains_north", MISTY_NORTH), ("misty_mountains", MISTY),
    ("misty_mountains_moria", MISTY_MORIA), ("misty_mountains_south", MISTY_SOUTH), ("erebor", EREBOR),
    ("iron_hills", IRON_HILLS), ("ered_nimrais_west", ERED_NIMRAIS_WEST), ("ered_nimrais", ERED_NIMRAIS),
    ("ered_nimrais_east", ERED_NIMRAIS_EAST), ("ered_lithui", ERED_LITHUI), ("ered_lithui_east", ERED_LITHUI_EAST),
    ("ephel_duath_1", EPHEL_DUATH_1), ("ephel_duath_mid", EPHEL_DUATH_MID), ("ephel_duath_2", EPHEL_DUATH_2),
]

# Passes: strips (polyline, width in tiles) where the land under the mountains is restored.
PASSES = {
    "High Pass": ([(0.478, 0.287), (0.54, 0.283)], 6),
}

# --------------------------------------------------------------------------------------------
# Water. The Belegaer coast runs from the north-west corner down Lindon, into the Gulf of Lhûn,
# along Minhiriath and Enedwaith to Cape Andrast, round the Bay of Belfalas and down to Umbar.
# --------------------------------------------------------------------------------------------
BELEGAER = [
    (0.0, 0.0), (0.075, 0.0), (0.065, 0.045), (0.04, 0.085), (0.03, 0.15), (0.025, 0.22), (0.03, 0.28),
    (0.035, 0.32),                                                                  # Forlindon
    (0.06, 0.335), (0.088, 0.343), (0.104, 0.35), (0.113, 0.358),                   # Gulf of Lhûn (Mithlond at the head)
    (0.1, 0.367), (0.07, 0.374), (0.04, 0.39), (0.03, 0.4),
    (0.022, 0.43), (0.035, 0.46), (0.07, 0.474), (0.11, 0.482), (0.14, 0.495),      # Harlindon
    (0.165, 0.51), (0.19, 0.53), (0.22, 0.555), (0.25, 0.58), (0.275, 0.6),         # Minhiriath
    (0.29, 0.617), (0.31, 0.635), (0.33, 0.66), (0.35, 0.68), (0.375, 0.698),       # Enedwaith, Isen mouth
    (0.385, 0.72), (0.375, 0.75), (0.39, 0.768), (0.42, 0.762),                     # Cape Andrast
    (0.45, 0.772), (0.48, 0.776), (0.495, 0.79), (0.507, 0.795), (0.516, 0.783),    # Anfalas, Dol Amroth
    (0.54, 0.775), (0.57, 0.778), (0.6, 0.79), (0.625, 0.806),                      # Bay of Belfalas, Ethir Anduin
    (0.638, 0.83), (0.622, 0.856), (0.6, 0.874), (0.575, 0.882),                    # Harondor coast
    (0.54, 0.878), (0.51, 0.884), (0.48, 0.888), (0.45, 0.893), (0.42, 0.9),        # Umbar
    (0.43, 0.92), (0.41, 0.95), (0.38, 1.0), (0.0, 1.0),
]
FOROCHEL = [(0.17, 0.0), (0.38, 0.0), (0.37, 0.03), (0.33, 0.052), (0.27, 0.062), (0.21, 0.052), (0.18, 0.03)]
SEAS = [BELEGAER, FOROCHEL]
ISLANDS = [("tolfalas", TOLFALAS)]

LAKE_EVENDIM = ("ellipse", 0.245, 0.205, 0.018, 0.022)
LONG_LAKE = ("ellipse", 0.778, 0.2, 0.007, 0.03)
NEN_HITHOEL = ("ellipse", 0.603, 0.515, 0.006, 0.017)
SEA_OF_RHUN = [(0.87, 0.36), (0.9, 0.33), (0.95, 0.32), (0.99, 0.34), (1.0, 0.36), (1.0, 0.47), (0.96, 0.49),
               (0.92, 0.475), (0.89, 0.44), (0.875, 0.4)]
SEA_OF_NURNEN = ("ellipse", 0.875, 0.745, 0.045, 0.02)
LAKES = {"Lake Evendim": LAKE_EVENDIM, "Long Lake": LONG_LAKE, "Nen Hithoel": NEN_HITHOEL,
         "Sea of Rhûn": SEA_OF_RHUN, "Sea of Nurnen": SEA_OF_NURNEN}

# Rivers: (polyline from source to mouth, width in tiles)
ANDUIN = ([(0.553, 0.075), (0.558, 0.12), (0.562, 0.18), (0.565, 0.235), (0.566, 0.27), (0.563, 0.32),
           (0.566, 0.37), (0.576, 0.415), (0.58, 0.45), (0.588, 0.48), (0.6, 0.5), (0.603, 0.515),
           (0.607, 0.53), (0.612, 0.545), (0.617, 0.565), (0.63, 0.59), (0.648, 0.62), (0.664, 0.645),
           (0.684, 0.665), (0.689, 0.7), (0.681, 0.735), (0.665, 0.76), (0.648, 0.785), (0.628, 0.81)], 4)
BRANDYWINE = ([(0.252, 0.225), (0.258, 0.25), (0.265, 0.28), (0.27, 0.305), (0.272, 0.33), (0.272, 0.36),
               (0.268, 0.39), (0.262, 0.415), (0.245, 0.44), (0.22, 0.465), (0.19, 0.49), (0.163, 0.512)], 3)
HOARWELL = ([(0.425, 0.13), (0.42, 0.18), (0.415, 0.24), (0.41, 0.29), (0.408, 0.34), (0.41, 0.39), (0.412, 0.42)], 2)
LOUDWATER = ([(0.497, 0.29), (0.482, 0.302), (0.466, 0.302), (0.453, 0.306), (0.44, 0.33), (0.425, 0.38),
              (0.412, 0.42)], 2)
GREYFLOOD = ([(0.412, 0.42), (0.395, 0.445), (0.378, 0.47), (0.365, 0.49), (0.345, 0.52), (0.32, 0.555),
              (0.3, 0.59), (0.285, 0.62)], 3)
ISEN = ([(0.489, 0.557), (0.49, 0.575), (0.486, 0.595), (0.47, 0.61), (0.45, 0.627), (0.425, 0.65), (0.4, 0.67),
         (0.37, 0.7)], 2)
ENTWASH = ([(0.515, 0.48), (0.535, 0.495), (0.56, 0.51), (0.575, 0.53), (0.59, 0.55), (0.6, 0.563), (0.616, 0.567)], 2)
CELDUIN = ([(0.78, 0.143), (0.779, 0.172), (0.778, 0.2), (0.78, 0.23), (0.79, 0.25), (0.81, 0.28), (0.835, 0.31),
            (0.86, 0.34), (0.885, 0.37)], 3)  # River Running: Erebor -> Long Lake -> Sea of Rhûn
FOREST_RIVER = ([(0.68, 0.095), (0.685, 0.13), (0.7, 0.17), (0.725, 0.185), (0.75, 0.195), (0.772, 0.2)], 2)
LHUN = ([(0.21, 0.12), (0.19, 0.17), (0.17, 0.22), (0.15, 0.27), (0.138, 0.31), (0.125, 0.345), (0.112, 0.358)], 2)
RIVERS = {"Anduin": ANDUIN, "Brandywine": BRANDYWINE, "Hoarwell": HOARWELL, "Loudwater": LOUDWATER,
          "Greyflood": GREYFLOOD, "Isen": ISEN, "Entwash": ENTWASH, "River Running": CELDUIN,
          "Forest River": FOREST_RIVER, "Lhûn": LHUN}

# Fords and bridges: discs (x, y, radius in tiles) where the land under the water is restored.
FORDS = {
    "Brandywine Bridge": (0.271, 0.322, 5), "Sarn Ford": (0.262, 0.414, 5), "Fords of Isen": (0.488, 0.587, 5),
    "Osgiliath": (0.683, 0.664, 6), "Cair Andros": (0.648, 0.62, 6), "Carrock ford": (0.565, 0.238, 5),
    "Old Ford": (0.566, 0.272, 5), "Tharbad": (0.366, 0.488, 5), "Last Bridge": (0.41, 0.29, 4),
    "Ford of Bruinen": (0.455, 0.305, 4), "Entwade": (0.588, 0.548, 4), "Celduin ford": (0.835, 0.31, 5),
    "Elvenking's bridge": (0.7, 0.171, 4), "Lhûn ford": (0.16, 0.245, 4),
}

# --------------------------------------------------------------------------------------------
# POI anchors: name -> (x, y, spread). spread = max distance from the anchor as a fraction of the world.
# The script writes offsetX/offsetY/radiusFactor so the engine places the POI there, and checks
# that the anchor area lies in the POI's biome. POIs not listed keep their random placement around
# the biome's bounding-box centre.
# --------------------------------------------------------------------------------------------
POI_ANCHORS = {
    "Spawn": (0.205, 0.345, 0.004),
    "Forest Capital": (0.22, 0.33, 0.005),         # Hobbiton
    "Island Capital": (0.468, 0.282, 0.004),       # Rivendell
    "Plains Capital": (0.66, 0.674, 0.004),        # Minas Tirith
    "Mountain Capital": (0.778, 0.125, 0.005),     # Erebor
    "Swamp Capital": (0.5, 0.905, 0.006),          # Umbar
    "ME Bree": (0.318, 0.33, 0.004),
    "ME Grey Havens": (0.103, 0.38, 0.003),
    "ME Thorins Halls": (0.108, 0.225, 0.004),
    "ME Tharbad": (0.378, 0.497, 0.004),
    "ME Beorns Hall": (0.585, 0.245, 0.004),
    "ME Dale": (0.792, 0.158, 0.003),
    "ME Lake-town": (0.762, 0.215, 0.003),
    "ME Thranduils Halls": (0.712, 0.157, 0.004),
    "ME Caras Galadhon": (0.555, 0.44, 0.004),
    "ME Iron Hills": (0.905, 0.132, 0.006),
    "ME Edoras": (0.555, 0.614, 0.004),
    "ME Helms Deep": (0.515, 0.618, 0.004),
    "ME Dol Amroth": (0.5, 0.775, 0.004),
    "ME Pelargir": (0.647, 0.762, 0.004),
    "ME Tolfalas": (0.592, 0.826, 0.003),
    "ME Lossoth Village": (0.4, 0.035, 0.006),
    "ME Harad Town": (0.78, 0.935, 0.04),
    "ME Nurn Town": (0.84, 0.78, 0.025),
    "ME Rhun Town": (0.93, 0.53, 0.02),
    "ME Easterling Camp": (0.95, 0.31, 0.012),
    "ME Barrow-downs": (0.305, 0.375, 0.004),
    "ME Old Forest": (0.296, 0.346, 0.004),
    "ME Weathertop": (0.375, 0.31, 0.004),
    "ME Trollshaws": (0.44, 0.27, 0.005),
    "ME Fornost": (0.29, 0.195, 0.005),
    "ME Carn Dum": (0.43, 0.1, 0.006),
    "ME Frozen Ruins": (0.86, 0.035, 0.008),
    "ME Ice Hall": (0.62, 0.025, 0.008),
    "ME Gundabad": (0.515, 0.075, 0.005),
    "ME Goblin-town": (0.507, 0.262, 0.004),
    "ME Moria": (0.494, 0.385, 0.004),
    "ME Isengard": (0.487, 0.545, 0.003),
    "ME Derndingle": (0.522, 0.515, 0.004),
    "ME Dol Guldur": (0.632, 0.415, 0.005),
    "ME Gladden Fields": (0.552, 0.365, 0.004),
    "ME Paths of the Dead": (0.545, 0.647, 0.003),
    "ME Osgiliath": (0.694, 0.66, 0.003),
    "ME Dead Marshes": (0.672, 0.56, 0.005),
    "Black Castle": (0.865, 0.63, 0.005),          # Barad-dûr
    "ME Mount Doom": (0.8, 0.67, 0.005),
    "ME Minas Morgul": (0.731, 0.665, 0.004),
    "ME Cirith Ungol": (0.735, 0.715, 0.004),
    "ME Morannon": (0.742, 0.588, 0.004),          # Black Gate
    "ME Haradrim Stronghold": (0.7, 0.965, 0.008),
    "ME Lion Den": (0.89, 0.91, 0.01),
    "ME Oasis": (0.78, 0.98, 0.008),
}

LABELS = {  # extra preview labels: name -> (x, y)
    "Gulf of Lhûn": (0.05, 0.36), "Bay of Belfalas": (0.53, 0.83), "Belegaer": (0.12, 0.7),
    "Ice Bay of Forochel": (0.22, 0.025), "Gap of Rohan": (0.49, 0.6),
}


# --------------------------------------------------------------------------------------------
def to_px(pts):
    return [(x * W, y * H) for x, y in pts]


def draw_shape(draw, shape, value):
    if isinstance(shape, tuple) and shape and shape[0] == "ellipse":
        _, cx, cy, rx, ry = shape
        draw.ellipse([(cx - rx) * W, (cy - ry) * H, (cx + rx) * W, (cy + ry) * H], fill=value)
    else:
        draw.polygon(to_px(shape), fill=value)


def rasterize(layers, fill):
    """layers: [(index, shape)] -> uint8 array (rows = y north..south), `fill` where nothing is painted."""
    img = Image.new("L", (W, H), fill)
    d = ImageDraw.Draw(img)
    for idx, shape in layers:
        draw_shape(d, shape, idx)
    return np.array(img)


def smooth_noise(seed, cells, amp):
    rng = np.random.default_rng(seed)
    out = np.zeros((H, W))
    for c, a in ((cells, 1.0), (cells * 3, 0.4)):
        g = Image.fromarray(rng.uniform(-1, 1, (c, c)).astype(np.float32), mode="F")
        out += a * np.array(g.resize((W, H), Image.BICUBIC))
    return out * amp


def warp(arr, seed, amp):
    dx = smooth_noise(seed, 24, amp)
    dy = smooth_noise(seed + 1, 24, amp)
    yy, xx = np.mgrid[0:H, 0:W]
    sx = np.clip(np.round(xx + dx), 0, W - 1).astype(int)
    sy = np.clip(np.round(yy + dy), 0, H - 1).astype(int)
    return arr[sy, sx]


def meander(pts, seed, amp=0.005, iters=3):
    """Two rounds of midpoint displacement (amp, amp/2) and Chaikin smoothing; endpoints stay fixed."""
    rng = np.random.default_rng(seed)
    out = list(pts)
    for a in (amp, amp / 2):
        nxt = [out[0]]
        for (x0, y0), (x1, y1) in zip(out, out[1:]):
            mx, my = (x0 + x1) / 2, (y0 + y1) / 2
            l = math.hypot(x1 - x0, y1 - y0) or 1
            j = rng.uniform(-a, a)
            nxt += [(mx - (y1 - y0) / l * j, my + (x1 - x0) / l * j), (x1, y1)]
        out = nxt
    for _ in range(iters):
        s = [out[0]]
        for (x0, y0), (x1, y1) in zip(out, out[1:]):
            s += [(0.75 * x0 + 0.25 * x1, 0.75 * y0 + 0.25 * y1), (0.25 * x0 + 0.75 * x1, 0.25 * y0 + 0.75 * y1)]
        s.append(out[-1])
        out = s
    return out


def draw_line(draw, pts, width, value):
    p = to_px(pts)
    draw.line(p, fill=value, width=width, joint="curve")
    r = width / 2
    for x, y in p:
        draw.ellipse([x - r, y - r, x + r, y + r], fill=value)


def build(names):
    idx = {n: i for i, n in enumerate(names)}
    OCEAN, NONE = idx["ocean"], 255
    lands = warp(rasterize([(idx[n], s) for n, s in LANDS], OCEAN), 11, 9)
    mountains = warp(rasterize([(idx[n], s) for n, s in MOUNTAINS], NONE), 23, 6)
    m = np.where(mountains != NONE, mountains, lands)
    # passes
    passes = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(passes)
    for pts, width in PASSES.values():
        draw_line(d, pts, width, 1)
    m = np.where(np.array(passes) == 1, lands, m)
    # seas (warped coast) and islands
    sea = warp(rasterize([(1, s) for s in SEAS], 0), 37, 10)
    m = np.where(sea == 1, OCEAN, m)
    isl = rasterize([(idx[n], s) for n, s in ISLANDS], NONE)
    m = np.where(isl != NONE, isl, m)
    before_water = m.copy()
    # lakes and rivers
    water = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(water)
    for shape in LAKES.values():
        draw_shape(d, shape, 1)
    for i, (pts, width) in enumerate(RIVERS.values()):
        draw_line(d, meander(pts, 100 + i), width, 1)
    m = np.where(np.array(water) == 1, OCEAN, m)
    # fords
    fords = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(fords)
    for x, y, r in FORDS.values():
        d.ellipse([x * W - r, y * H - r, x * W + r, y * H + r], fill=1)
    m = np.where(np.array(fords) == 1, before_water, m)
    return m


def load_biomes():
    world = json.load(open(os.path.join(PLANE, "world/world.json")))
    files, names, pois = [], [], {}
    for rel in world["biomesNames"]:
        d = json.load(open(os.path.join(PLANE, rel)))
        files.append(rel)
        names.append(d["name"])
        pois[d["name"]] = d.get("pointsOfInterest") or []
    return files, names, pois


def set_field(text, key, value):
    pat = re.compile(r'("%s"\s*:\s*)("[^"]*"|-?[0-9.eE+-]+)' % re.escape(key))
    if not pat.search(text):
        raise SystemExit("field %s not found" % key)
    return pat.sub(lambda mm: mm.group(1) + value, text, count=1)


def fmt(v):
    return ("%.4f" % v).rstrip("0").rstrip(".") if v != int(v) else "%d" % v if abs(v) >= 1 else "%.1f" % v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-data", action="store_true", help="only write the PNG and the preview")
    args = ap.parse_args()
    files, names, biome_pois = load_biomes()
    missing = set(names) - set(BIOME_COLORS)
    if missing:
        raise SystemExit("no colour for biomes: %s" % sorted(missing))
    if len(set(BIOME_COLORS.values())) != len(BIOME_COLORS):
        raise SystemExit("biome colours must be unique")
    m = build(names)
    rgb = np.zeros((H, W, 3), np.uint8)
    for i, n in enumerate(names):
        c = BIOME_COLORS[n]
        rgb[m == i] = [int(c[k:k + 2], 16) for k in (0, 2, 4)]
    os.makedirs(os.path.dirname(OUT_PNG), exist_ok=True)
    Image.fromarray(rgb, "RGB").save(OUT_PNG, optimize=True)
    print("wrote", os.path.relpath(OUT_PNG, ROOT))

    problems = 0
    bbox = {}
    print("%-24s %6s  %s" % ("biome", "cover%", "bbox x0-x1, y0-y1 (y north)"))
    for i, n in enumerate(names):
        ys, xs = np.nonzero(m == i)
        if len(xs) == 0:
            print("  !! biome %s has no pixels" % n)
            problems += 1
            continue
        bbox[n] = (xs.min(), xs.max() + 1, ys.min(), ys.max() + 1)
        print("%-24s %6.2f  %.3f-%.3f, %.3f-%.3f" % (n, 100.0 * len(xs) / (W * H), xs.min() / W, (xs.max() + 1) / W,
                                                     ys.min() / H, (ys.max() + 1) / H))

    # POI anchors: biome membership checks
    poi_biome = {p: b for b, ps in biome_pois.items() for p in ps}
    for name, (ax, ay, s) in POI_ANCHORS.items():
        b = poi_biome.get(name)
        if b is None:
            print("  !! anchored POI %s is in no biome" % name)
            problems += 1
            continue
        bi = names.index(b)
        r = max(1, int(s * W))
        cx, cy = int(ax * W), int(ay * H)
        win = m[max(0, cy - r):cy + r + 1, max(0, cx - r):cx + r + 1]
        share = float((win == bi).mean())
        if m[cy, cx] != bi or share < 0.5:
            print("  !! POI %-22s anchor (%.3f, %.3f) centre in %s, %.0f%% of its area in %s" %
                  (name, ax, ay, names[m[cy, cx]], 100 * share, b))
            problems += 1
    write_preview(m, names, bbox)
    if not args.no_data:
        write_data(files, names, bbox, poi_biome)
    print("problems:", problems)
    return 1 if problems else 0


def write_data(files, names, bbox, poi_biome):
    for rel, n in zip(files, names):
        p = os.path.join(PLANE, rel)
        t = open(p).read()
        t = set_field(t, "color", '"%s"' % BIOME_COLORS[n])
        if n != "ocean" and n in bbox:
            x0, x1, y0, y1 = bbox[n]
            t = set_field(t, "startPointX", fmt((x0 + x1) / 2 / W))
            t = set_field(t, "startPointY", fmt((y0 + y1) / 2 / H))
            t = set_field(t, "width", fmt((x1 - x0) / W))
            t = set_field(t, "height", fmt((y1 - y0) / H))
        d = json.loads(t)  # set_field edits the first match: make sure that was the top-level field
        if d["color"] != BIOME_COLORS[n] or (n in bbox and n != "ocean" and abs(d["width"] - (bbox[n][1] - bbox[n][0]) / W) > 1e-3):
            raise SystemExit("could not update top-level fields of " + rel)
        open(p, "w").write(t)
    # POI offsets. Engine: x = (startX + r cos t * w/2 + offsetX * w) * W, y(south up) = (1 - startY + r sin t * h/2 + offsetY * h) * H,
    # r = sqrt(rand / 2 * radiusFactor)
    p = os.path.join(PLANE, "world/points_of_interest.json")
    lines = open(p).read().split("\n")
    for i, line in enumerate(lines):
        mm = re.search(r'\{"name": "([^"]+)"', line)
        if not mm or mm.group(1) not in POI_ANCHORS:
            continue
        name = mm.group(1)
        ax, ay, s = POI_ANCHORS[name]
        b = poi_biome[name]
        x0, x1, y0, y1 = bbox[b]
        sx, sy, w, h = (x0 + x1) / 2 / W, (y0 + y1) / 2 / H, (x1 - x0) / W, (y1 - y0) / H
        ox, oy = (ax - sx) / w, (sy - ay) / h
        rf = 2 * (2 * s / max(w, h)) ** 2
        line = set_field(line, "radiusFactor", "%.6f" % rf)
        for key, val in (("offsetX", ox), ("offsetY", oy)):
            if '"%s"' % key in line:
                line = set_field(line, key, "%.5f" % val)
            else:
                line = line.rstrip().rstrip(",")
                assert line.endswith("}")
                line = line[:-1] + ', "%s": %.5f}' % (key, val) + ","
        lines[i] = line
    open(p, "w").write("\n".join(lines))
    # world.json
    p = os.path.join(PLANE, "world/world.json")
    t = open(p).read()
    sx, sy, _ = POI_ANCHORS["Spawn"]
    t = set_field(t, "playerStartPosX", fmt(sx))
    t = set_field(t, "playerStartPosY", fmt(1 - sy))
    if '"biomeMap"' not in t:
        t = t.replace('"biomesSprites"', '"biomeMap": "world/biome_map.png",\n\t"biomesSprites"', 1)
    open(p, "w").write(t)
    print("updated biome files, points_of_interest.json and world.json")


def write_preview(m, names, bbox):
    S = 2
    rgb = np.zeros((H, W, 3), np.uint8)
    for i, n in enumerate(names):
        c = BIOME_COLORS[n]
        rgb[m == i] = [int(c[k:k + 2], 16) for k in (0, 2, 4)]
    img = Image.fromarray(rgb, "RGB").resize((W * S, H * S), Image.NEAREST)
    d = ImageDraw.Draw(img)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 13)
        bold = ImageFont.truetype("DejaVuSans-Bold.ttf", 12)
    except OSError:
        font = bold = ImageFont.load_default()

    def text(x, y, s, f, fill):
        d.text((x * S * W, y * S * H), s, font=f, fill=fill, anchor="mm", stroke_width=2, stroke_fill="black")

    for n, (x0, x1, y0, y1) in bbox.items():
        if n == "ocean":
            continue
        ys, xs = np.nonzero(m == names.index(n))
        text(np.median(xs) / W, np.median(ys) / H, n, font, (255, 255, 255))
    for n, (pts, _) in RIVERS.items():
        x, y = pts[len(pts) // 3]
        text(x + 0.012, y, n, font, (140, 200, 255))
    for n, shape in LAKES.items():
        x, y = (shape[1], shape[2]) if shape[0] == "ellipse" else np.mean(shape, axis=0)
        text(x, y, n, font, (140, 200, 255))
    for n, (x, y) in LABELS.items():
        text(x, y, n, font, (140, 200, 255))
    for n, (x, y, r) in FORDS.items():
        d.ellipse([x * W * S - 4, y * H * S - 4, x * W * S + 4, y * H * S + 4], outline=(255, 255, 0))
    for n, (x, y, s) in POI_ANCHORS.items():
        px, py = x * W * S, y * H * S
        d.ellipse([px - 3, py - 3, px + 3, py + 3], fill=(255, 60, 60), outline="black")
        d.text((px + 5, py), n.replace("ME ", ""), font=bold, fill=(255, 230, 120), anchor="lm", stroke_width=2,
               stroke_fill="black")
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    img.save(PREVIEW)
    print("wrote", os.path.relpath(PREVIEW, ROOT))


if __name__ == "__main__":
    sys.exit(main())
