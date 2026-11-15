package forge.adventure.data;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Array;

import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.Current;
import forge.util.Aggregates;
import forge.util.MyRandom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information for the biomes
 */
public class BiomeData implements Serializable {
    public float startPointX;
    public float startPointY;
    public float noiseWeight;
    public float distWeight;
    public String name;
    public String tilesetAtlas;
    public String tilesetName;
    public BiomeTerrainData[] terrain;
    public float width;
    public float height;
    public String color;
    public boolean collision;
    public boolean invertHeight;
    public String[] spriteNames;
    public String[] enemies;
    public String[] pointsOfInterest;
    public BiomeStructureData[] structures;
    public EffectData effect;
    public boolean ignoreGlobalEffect;
    /** Fork (Middle-earth): flag-conditioned changes to {@link #enemies}, applied in order at spawn time. */
    public EnemyOverride[] enemyOverrides;

    /** One entry of {@link #enemyOverrides}. mode: replace (default), add or remove. */
    public static class EnemyOverride implements Serializable {
        public FlagCondition condition;
        public String mode = "replace";
        public String[] enemies;
    }

    private ArrayList<EnemyData> enemyList;
    private String enemyListKey; //Fork: which enemyOverrides matched when enemyList was built
    private ArrayList<PointOfInterestData> pointOfInterestList;

    private final Random rand = MyRandom.getRandom();

    public Color GetColor() {
        return Color.valueOf(color);
    }

    /** Fork (Middle-earth): indices of the matching enemyOverrides, "" when there are none. */
    private String matchingOverrides() {
        if (enemyOverrides == null || enemyOverrides.length == 0)
            return "";
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < enemyOverrides.length; i++) {
            EnemyOverride o = enemyOverrides[i];
            if (o != null && (o.condition == null || o.condition.eval()))
                key.append(i).append(',');
        }
        return key.toString();
    }

    /** Fork (Middle-earth): the enemy names after applying the matching enemyOverrides in list order. */
    public List<String> getEffectiveEnemyNames() {
        return effectiveEnemyNames(matchingOverrides());
    }

    private List<String> effectiveEnemyNames(String key) {
        List<String> names = new ArrayList<>();
        if (enemies != null)
            names.addAll(java.util.Arrays.asList(enemies));
        if (key.isEmpty())
            return names;
        for (String index : key.split(",")) {
            EnemyOverride o = enemyOverrides[Integer.parseInt(index)];
            List<String> list = o.enemies == null ? new ArrayList<>() : java.util.Arrays.asList(o.enemies);
            String mode = o.mode == null ? "replace" : o.mode.toLowerCase(java.util.Locale.ROOT);
            switch (mode) {
                case "add": names.addAll(list); break;
                case "remove": names.removeAll(list); break;
                default: names.clear(); names.addAll(list);
            }
        }
        return names;
    }

    public ArrayList<EnemyData> getEnemyList() {
        //Fork (Middle-earth): the cached list is rebuilt when the set of matching enemyOverrides changes.
        String key = matchingOverrides();
        if (enemyList != null && !key.equals(enemyListKey))
            enemyList = null;
        if (enemyList == null) {
            enemyListKey = key;
            enemyList = new ArrayList<>();
            List<String> enemies = effectiveEnemyNames(key);
            if (this.enemies == null && enemies.isEmpty())
                return enemyList;
            for (EnemyData data : new Array.ArrayIterator<>(WorldData.getAllEnemies())) {
                for (String enemyName : enemies) {
                    if (data.getName().equals(enemyName)) {
                        enemyList.add(data);
                        break;
                    }
                }
                //Adding enemy with 0 spawn rate allows quests to boost them and add to pool temporarily.
                EnemyData zeroSpawnRate = new EnemyData(data);
                zeroSpawnRate.spawnRate = 0.0f;
                enemyList.add(zeroSpawnRate);
            }
        }
        return enemyList;
    }

    public ArrayList<PointOfInterestData> getPointsOfInterest() {
        if (pointOfInterestList == null) {
            pointOfInterestList = new ArrayList<PointOfInterestData>();
            if (pointsOfInterest == null)
                return pointOfInterestList;
            Array<PointOfInterestData> allTowns = PointOfInterestData.getAllPointOfInterest();
            for (PointOfInterestData data : new Array.ArrayIterator<>(allTowns)) {
                for (String poiName : pointsOfInterest) {
                    if (data.name.equals(poiName)) {
                        pointOfInterestList.add(data);
                        break;
                    }
                }
            }
        }
        ArrayList<PointOfInterestData> cavesDungeon = new ArrayList<>();
        for (PointOfInterestData data : pointOfInterestList) {
            if (data.isDungeon()) {
                cavesDungeon.add(data);
            }
        }
        pointOfInterestList.removeAll(cavesDungeon);
        pointOfInterestList.addAll(cavesDungeon); //move to bottom..
        return pointOfInterestList;
    }

    public EnemyData getExtraSpawnEnemy(float difficultyFactor) {
        //todo: implement difficultyFactor
        List<EnemyData> extraSpawnEnemies = AdventureQuestController.instance().getExtraQuestSpawns(difficultyFactor);
        if (extraSpawnEnemies.isEmpty())
            return null;
        return Aggregates.random(extraSpawnEnemies); //fallback, shouldn't reach this point but guarantee that we return something
    }

    public EnemyData getEnemy(float difficultyFactor) {
        float totalDistribution = 0.0f;
        difficultyFactor = Current.player().getStatistic().rank(); // compare difficulty data to how many wins you have on your save
        List<EnemyData> filteredEnemies = new ArrayList<>();
        List<EnemyData> enemyList = getEnemyList(); //Fork: was the raw cache field (null before the first getEnemyList, and blind to overrides)
        for (EnemyData data : enemyList ){
            if (data.difficulty <= difficultyFactor) { 
                filteredEnemies.add(data);
                totalDistribution += data.spawnRate;
            }
        }
        // If no enemies match the criteria, fallback to a random enemy from the original list
        if (filteredEnemies.isEmpty()) {
            return Aggregates.random(enemyList);
        }

        // Perform weighted random selection
        float f = totalDistribution * rand.nextFloat();
        int i = 0;
        for (; i < filteredEnemies.size(); i++) {
            f -= filteredEnemies.get(i).spawnRate;
            if (f <= 0.0f) {
                return filteredEnemies.get(i);
            }
        }

        // Fallback, should not normally reach here
        return Aggregates.random(filteredEnemies);
    }

    private ArrayList<String> unusedTownNames;
    public String getNewTownName() {
        return Aggregates.removeRandom(getUnusedTownNames());
    }

    public ArrayList<String> getUnusedTownNames() {
        if (unusedTownNames == null) {
            unusedTownNames = WorldData.getTownNames(this.name);
        }
        return unusedTownNames;
    }
}