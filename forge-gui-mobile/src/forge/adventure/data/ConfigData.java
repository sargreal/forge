package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains general information about the game
 */
public class ConfigData {
    public int screenWidth;
    public int screenHeight;
    public String skin;
    public String font;
    public String fontColor;
    public int minDeckSize;
    public int maxNumberOfDecks;
    public float playerBaseSpeed;
    public String[] colorIds;
    public String[] colorIdNames;
    public String[] starterEditions;
    public String[] starterEditionNames;
    public ObjectMap<String, ObjectMap<String, String>> starterDecksByEdition;
    public DifficultyData[] difficulties;
    public RewardData legalCards;
    public String[] restrictedCards;
    public String[] restrictedEditions;
    public String[] restrictedBlocks;
    public String[] restrictedTokens;
    public String[] allowedEditions;
    public boolean vintageOnlyEditions = false;
    public String[] restrictedEvents;
    public String[] allowedEvents;
    public String[] allowedJumpstart;
    public String defaultBasicLandSet = "JMP";
    public boolean enableGeneticAI = true;
    public String chaosDeckFormat;
    public boolean usePriceListPrices = true;
    // Shards to reroll an offered inn event before joining it. 0 disables rerolls.
    public int eventRerollPrice;
    // Desktop default only; saved player choices take precedence on every platform.
    public boolean enableExtraBattleBackgroundsByDefault;
    public EffectData globalEffect;
    public ModifierData[] modifiers; //Optional rules the player can turn on at new game.
    public OriginData[] origins; //Fork (Middle-earth): game starts by race/colour; none = upstream start (Spawn, quest 28).
    public StatusFlag[] statusFlags; //Fork (run 2): flags shown in the game HUD (e.g. a corruption meter); none = nothing shown.
    public FlagEffect[] flagEffects; //Fork (run 2): duel effects for the player while a flag condition holds.

    /** Fork (run 2): one HUD readout of a flag, next to life, shards and gold. */
    public static class StatusFlag {
        public String flag;              //flag key
        public String scope = "quest";   //quest | character | map
        public String label;             //text before the value (default: the key)
        public String icon;              //optional skin icon name, written as [+icon] (e.g. "Shards")
        public int max = 0;              //> 0: shows "value/max"
        public boolean hidden = false;   //turn the readout off without deleting it
        public FlagCondition showWhen;   //optional: shown only while it holds
    }

    /** Fork (run 2): an EffectData applied to the player at duel start while {@code condition} holds. */
    public static class FlagEffect {
        public FlagCondition condition;
        public EffectData effect;
    }

}
