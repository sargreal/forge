import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.Json;
import forge.GuiMobile;
import forge.adventure.data.WorldData;
import forge.adventure.util.Config;
import forge.adventure.world.WorldMapTmx;
import forge.gui.GuiBase;

import java.util.Map;

/**
 * Rewrites the editor images of the plane's Tiled world with the engine's own writer: biomes.tsx/.png (the biome
 * tileset) and overview.png, in the colours of the editor palette (biome_palette.json next to the .world). Chunks, the
 * .world file and overview.tmx are not touched. Called by make_biome_palette.py; needs the mobile-dev fat jar.
 *
 *   cd forge-gui && java -Duser.home=<home with .forge/adventure/settings.json {"plane":"MiddleEarth"}> \
 *        -cp <fat jar> ../tools/middleearth/worldmap/BiomeTiles.java
 */
public class BiomeTiles {
    public static void main(String[] args) {
        GdxNativesLoader.load();
        GuiBase.setInterface(new GuiMobile("./"));
        Config cfg = Config.instance();
        WorldData data = new Json().fromJson(WorldData.class, cfg.getFile("world/world.json"));
        if (data.worldMap == null || data.worldMap.isEmpty()) {
            System.err.println("world.json has no worldMap");
            System.exit(1);
        }
        FileHandle world = cfg.getFile(data.worldMap);
        WorldMapTmx map = WorldMapTmx.read(world, data.GetBiomes(), data.width, data.height);
        FileHandle paletteFile = world.parent().child(WorldMapTmx.PALETTE_FILE);
        Map<String, WorldMapTmx.PaletteEntry> palette = WorldMapTmx.readPalette(paletteFile);
        WorldMapTmx.writeBiomeImages(world.parent(), data, map.biomes, palette);
        System.out.println("wrote " + world.parent().path() + "/{biomes.tsx,biomes.png,overview.png} with "
                + (palette.isEmpty() ? "the biome colours (no " + WorldMapTmx.PALETTE_FILE + ")" : palette.size() + " palette colours"));
    }
}
