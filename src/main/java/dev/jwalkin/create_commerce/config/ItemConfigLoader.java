package dev.jwalkin.create_commerce.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ItemConfigLoader {

    private static final Logger LOGGER = LogManager.getLogger("create_commerce");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String CONFIG_FOLDER = "create_commerce";
    public static final String CONFIG_FILE = "create_commerce-items.json";
    public static final String DEFAULT_RESOURCE = "/default_configs/create_commerce-items.json";

    public record ItemValue(int payout, int dailyCap, int inputAmount) {}

    private static String unknownItemBehaviour = "ignore";
    private static final List<String> refusedItems = new ArrayList<>();
    private static final List<TagTierEntry> tagTiers = new ArrayList<>();
    private static final Map<String, ItemValue> itemOverrides = new HashMap<>();

    public record TagTierEntry(List<String> tags, int payout, int dailyCap, int inputAmount) {}

    // Auto-detected values for modded items not in config
    private static final Map<String, ItemValue> autoValues = new HashMap<>();

    // Path to the config file
    private static Path configFilePath;

    public static void load(Path configDir) {
        Path subDir = configDir.resolve(CONFIG_FOLDER);
        configFilePath = subDir.resolve(CONFIG_FILE);
        if (!Files.exists(configFilePath)) {
            copyDefault(subDir, configFilePath);
        }

        try (var reader = Files.newBufferedReader(configFilePath, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            parse(root);
            LOGGER.info("[Create: Commerce] Loaded item config from {}", configFilePath);
        } catch (Exception e) {
            LOGGER.error("[Create: Commerce] Failed to load {}: {}", configFilePath, e.getMessage());
            LOGGER.warn("[Create: Commerce] Using empty item config — all items will follow unknown_item_behaviour.");
        }
    }

    /**
     * Runs the auto-detection scan. Should be called after the server has fully started.
     */
    public static void runAutoDetection() {
        autoDetectModdedItems();
    }

    private static void parse(JsonObject root) {
        refusedItems.clear();
        tagTiers.clear();
        itemOverrides.clear();

        // Unknown item behaviour
        if (root.has("unknown_item_behaviour")) {
            unknownItemBehaviour = root.get("unknown_item_behaviour").getAsString();
        }

        // Refused items
        if (root.has("refused_items")) {
            for (JsonElement el : root.getAsJsonArray("refused_items")) {
                refusedItems.add(el.getAsString());
            }
        }

        // Tag tiers
        if (root.has("tag_tiers")) {
            JsonObject tiers = root.getAsJsonObject("tag_tiers");
            for (String key : List.of("tier1", "tier2", "tier3", "tier4", "tier5")) {
                if (!tiers.has(key)) continue;
                JsonObject tier = tiers.getAsJsonObject(key);
                int payout = tier.has("payout_spurs") ? tier.get("payout_spurs").getAsInt()
                           : tier.has("payout_emeralds") ? tier.get("payout_emeralds").getAsInt() : 1;
                int cap = tier.has("daily_cap") ? tier.get("daily_cap").getAsInt() : -1;
                int input = tier.has("input_amount") ? tier.get("input_amount").getAsInt() : 1;
                if (input < 1) input = 1;
                List<String> tags = new ArrayList<>();
                if (tier.has("tags")) {
                    for (JsonElement el : tier.getAsJsonArray("tags")) {
                        tags.add(el.getAsString());
                    }
                }
                tagTiers.add(new TagTierEntry(tags, payout, cap, input));
            }
        }

        // Item overrides
        if (root.has("item_overrides")) {
            parseItemOverrides(root.getAsJsonObject("item_overrides"));
        }
    }

    /** Parses item overrides, recursing into nested mod-group sub-objects. */
    private static void parseItemOverrides(JsonObject overrides) {
        for (Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
            String k = entry.getKey();
            if (k.startsWith("_")) continue;
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject val = entry.getValue().getAsJsonObject();

            if (val.has("payout_spurs") || val.has("payout_emeralds")) {
                // Direct item entry
                int payout = val.has("payout_spurs") ? val.get("payout_spurs").getAsInt()
                           : val.has("payout_emeralds") ? val.get("payout_emeralds").getAsInt() : 0;
                int cap = val.has("daily_cap") ? val.get("daily_cap").getAsInt() : -1;
                int input = val.has("input_amount") ? val.get("input_amount").getAsInt() : 1;
                if (input < 1) input = 1;
                itemOverrides.put(k, new ItemValue(payout, cap, input));
            } else {
                // Nested mod group; recurse
                parseItemOverrides(val);
            }
        }
    }

    private static void copyDefault(Path subDir, Path dest) {
        try (InputStream in = ItemConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[Create: Commerce] Default item config resource not found at {}", DEFAULT_RESOURCE);
                return;
            }
            Files.createDirectories(subDir);
            Files.copy(in, dest);
            LOGGER.info("[Create: Commerce] Created default item config at {}", dest);
        } catch (IOException e) {
            LOGGER.error("[Create: Commerce] Could not write default item config: {}", e.getMessage());
        }
    }

    public static boolean isRefused(ItemStack stack) {
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        for (String entry : refusedItems) {
            if (entry.startsWith("#")) {
                TagKey<Item> tag = TagKey.create(Registries.ITEM,
                        ResourceLocation.parse(entry.substring(1)));
                if (stack.is(tag)) return true;
            } else {
                if (entry.equals(itemId)) return true;
            }
        }
        return false;
    }

    /**
     * Looks up the deposit payout for {@code stack} when this depot has explicitly
     * accepted the item via its village profile. If an item has no entry in the
     * config (no tag tier match, no item override), it returns 0 and will
     * NOT be tradeable. The config is the sole authority on pricing.
     *
     * <p>Caller should guard with {@link #isRefused(ItemStack)} before invoking.
     * Refused items still pay 0.
     */
    public static int getValueInEmeraldsForAcceptedItem(ItemStack stack) {
        return getValueInEmeralds(stack);
    }

    public static int getValueInEmeralds(ItemStack stack) {
        if (isRefused(stack)) return 0;

        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();

        // Check item overrides first
        if (itemOverrides.containsKey(itemId)) {
            ItemValue val = itemOverrides.get(itemId);
            int payout = val.payout();
            double multiplier = CCConfig.SERVER.globalPayoutMultiplier.get();
            return (int) Math.round(payout * multiplier);
        }

        // Check tag tiers in order
        for (TagTierEntry tier : tagTiers) {
            for (String tagStr : tier.tags()) {
                if (tagStr.startsWith("#")) {
                    TagKey<Item> tag = TagKey.create(Registries.ITEM,
                            ResourceLocation.parse(tagStr.substring(1)));
                    if (stack.is(tag)) {
                        double multiplier = CCConfig.SERVER.globalPayoutMultiplier.get();
                        return (int) Math.round(tier.payout() * multiplier);
                    }
                } else {
                    if (tagStr.equals(itemId)) {
                        double multiplier = CCConfig.SERVER.globalPayoutMultiplier.get();
                        return (int) Math.round(tier.payout() * multiplier);
                    }
                }
            }
        }

        // Auto-detected modded items
        if (autoValues.containsKey(itemId)) {
            int payout = autoValues.get(itemId).payout();
            double multiplier = CCConfig.SERVER.globalPayoutMultiplier.get();
            return (int) Math.round(payout * multiplier);
        }

        // Fall back to unknown_item_behaviour
        return switch (unknownItemBehaviour) {
            case "tier1" -> (int) Math.round(CCConfig.SERVER.globalPayoutMultiplier.get());
            default -> 0; // "ignore" or "refuse"
        };
    }

    /**
     * Returns the configured input_amount for {@code stack} Defaults to 1. 
     * Item overrides take priority over tier defaults; unknown items return 1.
     */
    public static int getInputAmount(ItemStack stack) {
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        if (itemOverrides.containsKey(itemId)) {
            return Math.max(1, itemOverrides.get(itemId).inputAmount());
        }
        for (TagTierEntry tier : tagTiers) {
            for (String tagStr : tier.tags()) {
                if (tagStr.startsWith("#")) {
                    TagKey<Item> tag = TagKey.create(Registries.ITEM,
                            ResourceLocation.parse(tagStr.substring(1)));
                    if (stack.is(tag)) return Math.max(1, tier.inputAmount());
                } else if (tagStr.equals(itemId)) {
                    return Math.max(1, tier.inputAmount());
                }
            }
        }
        if (autoValues.containsKey(itemId)) {
            return Math.max(1, autoValues.get(itemId).inputAmount());
        }
        return 1;
    }

    /** Resolve input_amount for a registered item id. */
    public static int getInputAmountForId(String itemId) {
        if (itemOverrides.containsKey(itemId)) {
            return Math.max(1, itemOverrides.get(itemId).inputAmount());
        }
        return 1;
    }

    /**
     * Returns the per-item daily cap for {@code stack}. Item overrides win over tier defaults.
     */
    public static int getDailyCap(ItemStack stack) {
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        if (itemOverrides.containsKey(itemId)) {
            return itemOverrides.get(itemId).dailyCap();
        }
        for (TagTierEntry tier : tagTiers) {
            for (String tagStr : tier.tags()) {
                if (tagStr.startsWith("#")) {
                    TagKey<Item> tag = TagKey.create(Registries.ITEM,
                            ResourceLocation.parse(tagStr.substring(1)));
                    if (stack.is(tag)) return tier.dailyCap();
                } else if (tagStr.equals(itemId)) {
                    return tier.dailyCap();
                }
            }
        }
        if (autoValues.containsKey(itemId)) {
            return autoValues.get(itemId).dailyCap();
        }
        return -1;
    }

    // Auto-detection: assigns values to modded items not in config

    /**
     * Scans registered items and auto-prices modded items not in config.
     * Writes results back to the config file so users can fine-tune values.
     */
    private static void autoDetectModdedItems() {
        if (!CCConfig.SERVER.enableAutoDetection.get()) {
            LOGGER.info("[Create: Commerce] Auto-detection disabled by config");
            return;
        }

        autoValues.clear();
        // Group detected items by mod namespace for organized config output
        Map<String, Map<String, ItemValue>> itemsByMod = new TreeMap<>();

        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            try {
                ResourceLocation rl = entry.getKey().location();
                String itemId = rl.toString();

                // Skip vanilla items (already covered by default config)
                if ("minecraft".equals(rl.getNamespace())) continue;

                // Skip if already configured
                if (itemOverrides.containsKey(itemId)) continue;
                if (isCoveredByTagTier(entry.getValue())) continue;
                if (isRefusedById(itemId)) continue;

                Item item = entry.getValue();

                // Skip non-food placeable blocks
                if (item instanceof BlockItem blockItem) {
                    ItemStack blockStack = blockItem.getDefaultInstance();
                    FoodProperties blockFood = blockItem.getFoodProperties(blockStack, null);
                    if (blockFood == null) {
                        blockFood = blockStack.get(DataComponents.FOOD);
                    }
                    if (blockFood == null) continue;
                }

                int payout = detectPayout(item, itemId);
                if (payout > 0) {
                    int dailyCap = computeDailyCap(item, payout);
                    ItemValue value = new ItemValue(payout, dailyCap, 1);
                    autoValues.put(itemId, value);
                    itemsByMod.computeIfAbsent(rl.getNamespace(), k -> new LinkedHashMap<>())
                            .put(itemId, value);
                }
            } catch (Exception e) {
                // Skip items that throw during property access
                LOGGER.debug("[Create: Commerce] Skipped item during auto-detection: {}",
                        e.getMessage());
            }
        }

        LOGGER.info("[Create: Commerce] Auto-detected {} modded items", autoValues.size());

        // Write detected items back to config file
        if (!itemsByMod.isEmpty() && configFilePath != null) {
            writeAutoDetectedToConfig(itemsByMod);
        }
    }

    /** Writes auto-detected items into config, organized by mod namespace. */
    private static void writeAutoDetectedToConfig(Map<String, Map<String, ItemValue>> itemsByMod) {
        try {
            // Re-read the current config file as a JsonObject
            JsonObject root;
            try (var reader = Files.newBufferedReader(configFilePath, StandardCharsets.UTF_8)) {
                root = GSON.fromJson(reader, JsonObject.class);
            }

            if (root == null) {
                root = new JsonObject();
            }

            // Get or create the item_overrides section
            JsonObject overrides = root.has("item_overrides")
                    ? root.getAsJsonObject("item_overrides")
                    : new JsonObject();

            int added = 0;
            for (Map.Entry<String, Map<String, ItemValue>> modEntry : itemsByMod.entrySet()) {
                String namespace = modEntry.getKey();
                Map<String, ItemValue> items = modEntry.getValue();

                // Create or reuse a nested sub-object for this mod's items
                JsonObject modGroup = overrides.has(namespace) && overrides.get(namespace).isJsonObject()
                        ? overrides.getAsJsonObject(namespace)
                        : new JsonObject();

                for (Map.Entry<String, ItemValue> itemEntry : items.entrySet()) {
                    String itemId = itemEntry.getKey();
                    if (!modGroup.has(itemId)) {
                        ItemValue val = itemEntry.getValue();
                        JsonObject itemJson = new JsonObject();
                        itemJson.addProperty("payout_spurs", val.payout());
                        itemJson.addProperty("input_amount", val.inputAmount());
                        itemJson.addProperty("daily_cap", val.dailyCap());
                        modGroup.add(itemId, itemJson);
                        added++;
                    }
                }

                overrides.add(namespace, modGroup);
            }

            root.add("item_overrides", overrides);

            // Write the updated config back
            Files.writeString(configFilePath, GSON.toJson(root), StandardCharsets.UTF_8);
            LOGGER.info("[Create: Commerce] Wrote {} auto-detected items to config for {} mods",
                    added, itemsByMod.size());

        } catch (Exception e) {
            LOGGER.error("[Create: Commerce] Failed to write auto-detected items to config: {}", e.getMessage());
        }
    }

    /** Check if item is already matched by a tag tier. */
    private static boolean isCoveredByTagTier(Item item) {
        ItemStack stack = item.getDefaultInstance();
        for (TagTierEntry tier : tagTiers) {
            for (String tagStr : tier.tags()) {
                if (tagStr.startsWith("#")) {
                    TagKey<Item> tag = TagKey.create(Registries.ITEM,
                            ResourceLocation.parse(tagStr.substring(1)));
                    if (stack.is(tag)) return true;
                }
            }
        }
        return false;
    }

    /** Check if an item ID is in the refused list. */
    private static boolean isRefusedById(String itemId) {
        return refusedItems.contains(itemId);
    }

    /**
     * Detects payout for an item using a cascading strategy:
     * 1. Food items → nutrition/saturation based
     * 2. Swords/weapons → damage-based comparison to vanilla
     * 3. Tools (pick/shovel/axe/hoe) → tier-based comparison to vanilla
     * 4. Armor → defense/toughness-based comparison to vanilla
     * 5. Mob drops → known-drop-list heuristic
     * 6. Ingots/gems/raw materials → generic material heuristic
     * 7. Everything else → low default
     */
    private static int detectPayout(Item item, String itemId) {
        // 1. Food items — check both the legacy method and DataComponents
        ItemStack stack = item.getDefaultInstance();
        FoodProperties food = item.getFoodProperties(stack, null);
        if (food == null) {
            food = stack.get(DataComponents.FOOD);
        }
        if (food != null) {
            return detectFoodPayout(food, item);
        }

        // 2. Any tiered item
        if (item instanceof TieredItem tiered) {
            float attackDamage = getAttackDamage(tiered);
            float speed = tiered.getTier().getSpeed();
            if (attackDamage >= 3.0f) {
                return detectWeaponPayout(attackDamage);
            } else {
                return detectToolPayout(speed);
            }
        }

        // 3. Bows and crossbows
        if (item instanceof ProjectileWeaponItem) {
            return 8; // roughly iron-tier value for ranged weapons
        }

        // 4. Armor items
        if (item instanceof ArmorItem armor) {
            return detectArmorPayout(armor);
        }

        // 5. Generic mob drops and materials (by name pattern)
        int materialPayout = detectMaterialPayout(itemId, item);
        if (materialPayout > 0) {
            return materialPayout;
        }

        // 6. Default: assign a minimal value so modded items are tradeable
        return 1;
    }

    // -- Food --

    /** Food pricing: score = nutrition + saturation*2, scaled to 0.5 spurs/point. Cap 16. */
    private static int detectFoodPayout(FoodProperties food, Item item) {
        double nutrition = food.nutrition();
        double saturation = food.saturation();
        double score = nutrition + (saturation * 2.0);

        int payout = Math.max(1, (int) Math.round(score * 0.5));
        return Math.min(payout, 16); // cap at iron-ingot value
    }

    // -- Weapons --

    /** Weapon pricing by attack damage. wood(4)→2, stone(5)→4, iron(6)→8, diamond(7)→16, netherite(8+)→32 */
    private static int detectWeaponPayout(float attackDamage) {
        if (attackDamage >= 8) return 32;  // netherite-tier
        if (attackDamage >= 7) return 16;  // diamond-tier
        if (attackDamage >= 6) return 8;   // iron-tier
        if (attackDamage >= 5) return 4;   // stone-tier
        return 2;                          // wood-tier or below
    }

    // -- Tools --

    /** Tool pricing by mining speed. wood(2)→2, stone(4)→4, iron(6)→8, diamond(8)→16, netherite(9+)→24 */
    private static int detectToolPayout(float speed) {
        if (speed >= 9) return 24;  // netherite-tier
        if (speed >= 8) return 16;  // diamond-tier
        if (speed >= 6) return 8;   // iron-tier
        if (speed >= 4) return 4;   // stone-tier
        return 2;                   // wood-tier or below
    }

    // -- Armor --

    /** Armor pricing by max durability. <120→2, <280→8, <500→12, <600→16, ≥600→32 */
    private static int detectArmorPayout(ArmorItem armor) {
        ItemStack stack = armor.getDefaultInstance();
        int durability = armor.getMaxDamage(stack);
        if (durability >= 600) return 32;   // netherite-like
        if (durability >= 500) return 16;   // diamond-like
        if (durability >= 280) return 12;   // between iron and diamond
        if (durability >= 120) return 8;    // iron/chain-like
        if (durability >= 50)  return 4;    // gold-like
        return 2;                           // leather-like
    }

    // -- Materials --

    /** Detect common modded material patterns by name. Returns 0 if no pattern matched. */
    private static int detectMaterialPayout(String itemId, Item item) {
        String path = ResourceLocation.parse(itemId).getPath();

        // Ingot-like items
        if (path.endsWith("_ingot") || path.equals("ingot")) {
            return 16; // default to iron-tier
        }
        // Raw ore / raw material
        if (path.startsWith("raw_") || path.endsWith("_ore") || path.contains("_raw_")) {
            return 8; // raw materials worth half the ingot
        }
        // Gem-like items
        if (path.endsWith("_gem") || path.equals("gem") || path.endsWith("_crystal")) {
            return 32; // default to redstone-tier gem
        }
        // Dust / powder
        if (path.endsWith("_dust") || path.endsWith("_powder") || path.endsWith("_crushed")) {
            return 4; // processed intermediate, low value
        }
        // Nugget
        if (path.endsWith("_nugget") || path.equals("nugget")) {
            return 2; // 1/8 of ingot
        }
        // Plate / sheet
        if (path.endsWith("_plate") || path.endsWith("_sheet")) {
            return 16; // similar to ingot
        }
        // Gear / rod / wire
        if (path.endsWith("_gear") || path.endsWith("_rod") || path.endsWith("_wire")) {
            return 4; // crafting components
        }
        // Dye / bottle / bucket contents
        if (path.contains("dye") || path.endsWith("_bucket") || path.endsWith("_bottle")) {
            return 2;
        }

        return 0; // no pattern matched
    }

    /** Total attack damage = tier bonus + base (1.0). */
    private static float getAttackDamage(TieredItem tiered) {
        return tiered.getTier().getAttackDamageBonus() + 1.0f;
    }

    /**
     * Computes an appropriate daily cap for auto-detected items.
     * Tools, weapons, and armor are capped at 16/day or less based on payout value.
     * Other items (food, materials) have no specific cap.
     */
    private static int computeDailyCap(Item item, int payout) {
        if (item instanceof TieredItem || item instanceof ArmorItem || item instanceof ProjectileWeaponItem) {
            if (payout >= 32) return 2;
            if (payout >= 16) return 4;
            if (payout >= 8)  return 8;
            return 16;
        }
        return -1;
    }
}
