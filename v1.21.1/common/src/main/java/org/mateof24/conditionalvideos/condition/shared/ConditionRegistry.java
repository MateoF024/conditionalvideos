package org.mateof24.conditionalvideos.condition.shared;

import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ConditionConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.ScoreboardConditionConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConditionRegistry {
    public static final String KEY_FIRST_JOIN = "firstJoin";
    public static final String KEY_PLAYER_DEATH = "playerDeath";
    public static final String KEY_TOTEM_USED = "totemUsed";
    public static final String KEY_BED_SLEEP = "bedSleep";
    public static final String TYPE_ENTITY_KILLED = "entityKilled";
    public static final String TYPE_DEATH_BY_ENTITY = "deathByEntity";
    public static final String TYPE_ADVANCEMENT = "advancement";
    public static final String TYPE_DIMENSION = "dimension";
    public static final String TYPE_ITEM_OBTAINED = "itemObtained";
    public static final String TYPE_ITEM_CRAFTED = "itemCrafted";
    public static final String TYPE_RECIPE_UNLOCKED = "recipeUnlocked";
    public static final String TYPE_SCOREBOARD = "scoreboard";
    public static final String TYPE_CUSTOM = "custom";

    private ConditionRegistry() {
    }

    public static List<String> listPlayableKeys(ConditionalVideosConfig config) {
        List<String> keys = new ArrayList<>();
        if (config == null) {
            return keys;
        }
        addIfPlayable(keys, KEY_FIRST_JOIN, config.firstJoin());
        addIfPlayable(keys, KEY_PLAYER_DEATH, config.playerDeath());
        addIfPlayable(keys, KEY_TOTEM_USED, config.totemUsed());
        addIfPlayable(keys, KEY_BED_SLEEP, config.bedSleep());
        addMapKeys(keys, TYPE_ENTITY_KILLED, config.entityKilled());
        addMapKeys(keys, TYPE_DEATH_BY_ENTITY, config.deathByEntity());
        addMapKeys(keys, TYPE_ADVANCEMENT, config.advancementCompleted());
        addMapKeys(keys, TYPE_DIMENSION, config.dimensionChanged());
        addMapKeys(keys, TYPE_ITEM_OBTAINED, config.itemObtained());
        addMapKeys(keys, TYPE_ITEM_CRAFTED, config.itemCrafted());
        addMapKeys(keys, TYPE_RECIPE_UNLOCKED, config.recipeUnlocked());
        addMapKeys(keys, TYPE_CUSTOM, config.custom());
        for (Map.Entry<String, ScoreboardConditionConfig> entry : config.scoreboard().entrySet()) {
            if (entry.getValue() != null && !entry.getValue().resolvedPlaylist().isEmpty()) {
                keys.add(TYPE_SCOREBOARD + "/" + entry.getKey());
            }
        }
        return keys;
    }

    public static ConditionConfig resolve(ConditionalVideosConfig config, String key) {
        if (config == null || key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (KEY_FIRST_JOIN.equals(trimmed)) {
            return config.firstJoin();
        }
        if (KEY_PLAYER_DEATH.equals(trimmed)) {
            return config.playerDeath();
        }
        if (KEY_TOTEM_USED.equals(trimmed)) {
            return config.totemUsed();
        }
        if (KEY_BED_SLEEP.equals(trimmed)) {
            return config.bedSleep();
        }
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            return null;
        }
        String type = trimmed.substring(0, slash);
        String mapKey = trimmed.substring(slash + 1);
        if (TYPE_SCOREBOARD.equals(type)) {
            ScoreboardConditionConfig scoreboard = config.scoreboard().get(mapKey);
            return scoreboard == null ? null : scoreboard.toConditionConfig();
        }
        return switch (type) {
            case TYPE_ENTITY_KILLED -> config.entityKilled().get(mapKey);
            case TYPE_DEATH_BY_ENTITY -> config.deathByEntity().get(mapKey);
            case TYPE_ADVANCEMENT -> config.advancementCompleted().get(mapKey);
            case TYPE_DIMENSION -> config.dimensionChanged().get(mapKey);
            case TYPE_ITEM_OBTAINED -> config.itemObtained().get(mapKey);
            case TYPE_ITEM_CRAFTED -> config.itemCrafted().get(mapKey);
            case TYPE_RECIPE_UNLOCKED -> config.recipeUnlocked().get(mapKey);
            case TYPE_CUSTOM -> config.custom().get(mapKey);
            default -> null;
        };
    }

    private static void addIfPlayable(List<String> keys, String key, ConditionConfig config) {
        if (config != null && !config.resolvedPlaylist().isEmpty()) {
            keys.add(key);
        }
    }

    private static void addMapKeys(List<String> keys, String type, Map<String, ConditionConfig> map) {
        if (map == null) {
            return;
        }
        for (Map.Entry<String, ConditionConfig> entry : map.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().resolvedPlaylist().isEmpty()) {
                keys.add(type + "/" + entry.getKey());
            }
        }
    }
}
