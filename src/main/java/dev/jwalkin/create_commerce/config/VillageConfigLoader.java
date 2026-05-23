package dev.jwalkin.create_commerce.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VillageConfigLoader {

    private static final Logger LOGGER = LogManager.getLogger("create_commerce");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String CONFIG_FOLDER = "create_commerce";
    public static final String CONFIG_FILE = "create_commerce-villages.json";
    public static final String DEFAULT_RESOURCE = "/default_configs/create_commerce-villages.json";

    public record VillageProfile(
            String id,
            String displayName,
            List<String> accepts,
            List<String> bonusAccepts,
            List<String> refuses
    ) {}

    private static final Map<String, VillageProfile> profiles = new HashMap<>();
    private static final Map<String, Integer> profileWeights = new HashMap<>();
    private static final Map<String, String> biomeOverrides = new HashMap<>();
    private static final Map<UUID, String> villageOverrides = new HashMap<>();

    public static void load(Path configDir) {
        Path subDir = configDir.resolve(CONFIG_FOLDER);
        Path configFile = subDir.resolve(CONFIG_FILE);
        if (!Files.exists(configFile)) {
            copyDefault(subDir, configFile);
        }

        try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            parse(root);
            LOGGER.info("[Create: Commerce] Loaded village config from {}", configFile);
        } catch (Exception e) {
            LOGGER.error("[Create: Commerce] Failed to load {}: {}", configFile, e.getMessage());
        }
    }

    private static void parse(JsonObject root) {
        profiles.clear();
        profileWeights.clear();
        biomeOverrides.clear();
        villageOverrides.clear();

        if (root.has("profile_weights")) {
            JsonObject weights = root.getAsJsonObject("profile_weights");
            for (Map.Entry<String, JsonElement> e : weights.entrySet()) {
                if (!e.getKey().startsWith("_")) {
                    profileWeights.put(e.getKey(), e.getValue().getAsInt());
                }
            }
        }

        if (root.has("profiles")) {
            JsonObject profs = root.getAsJsonObject("profiles");
            for (Map.Entry<String, JsonElement> e : profs.entrySet()) {
                if (e.getKey().startsWith("_") || !e.getValue().isJsonObject()) continue;
                JsonObject p = e.getValue().getAsJsonObject();
                String displayName = p.has("display_name") ? p.get("display_name").getAsString() : e.getKey();
                List<String> accepts = parseStringList(p, "accepts");
                List<String> bonusAccepts = parseStringList(p, "bonus_accepts");
                List<String> refuses = parseStringList(p, "refuses");
                profiles.put(e.getKey(), new VillageProfile(e.getKey(), displayName, accepts, bonusAccepts, refuses));
            }
        }

        if (root.has("biome_overrides")) {
            JsonObject bio = root.getAsJsonObject("biome_overrides");
            for (Map.Entry<String, JsonElement> e : bio.entrySet()) {
                if (!e.getKey().startsWith("_")) {
                    biomeOverrides.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }

        if (root.has("village_overrides")) {
            JsonObject ov = root.getAsJsonObject("village_overrides");
            for (Map.Entry<String, JsonElement> e : ov.entrySet()) {
                if (e.getKey().startsWith("_")) continue;
                try {
                    villageOverrides.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
                } catch (IllegalArgumentException ignored) {
                    // skip invalid UUIDs (like the example entry)
                }
            }
        }
    }

    private static List<String> parseStringList(JsonObject obj, String key) {
        List<String> result = new ArrayList<>();
        if (obj.has(key)) {
            for (JsonElement el : obj.getAsJsonArray(key)) {
                result.add(el.getAsString());
            }
        }
        return result;
    }

    private static void copyDefault(Path subDir, Path dest) {
        try (InputStream in = VillageConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[Create: Commerce] Default village config resource not found at {}", DEFAULT_RESOURCE);
                return;
            }
            Files.createDirectories(subDir);
            Files.copy(in, dest);
            LOGGER.info("[Create: Commerce] Created default village config at {}", dest);
        } catch (IOException e) {
            LOGGER.error("[Create: Commerce] Could not write default village config: {}", e.getMessage());
        }
    }

    public static VillageProfile getProfile(String id) {
        return profiles.getOrDefault(id, getDefaultProfile());
    }

    public static VillageProfile getDefaultProfile() {
        return profiles.getOrDefault("farming_settlement",
                new VillageProfile("farming_settlement", "Farming Settlement",
                        List.of(), List.of(), List.of()));
    }

    public static String getProfileForBiome(String biomeId) {
        return biomeOverrides.get(biomeId);
    }

    public static String getProfileOverrideForVillage(UUID villageId) {
        return villageOverrides.get(villageId);
    }

    public static Map<String, Integer> getProfileWeights() {
        return Map.copyOf(profileWeights);
    }

    public static Map<String, VillageProfile> getAllProfiles() {
        return Map.copyOf(profiles);
    }
}
