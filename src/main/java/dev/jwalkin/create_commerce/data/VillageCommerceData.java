package dev.jwalkin.create_commerce.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class VillageCommerceData extends SavedData {

    private static final Logger LOGGER = LogManager.getLogger("create_commerce");
    private static final String DATA_NAME = "create_commerce_villages";

    public static class VillageRecord {
        public String profileName = "farming_settlement";
        public String customName = "";
        // Per-item daily consumption tracking (item ID → consumed count).
        public final Map<String, Integer> capConsumed = new HashMap<>();
        public long lastResetDay = -1L;
        // Names this village broadcasts under. Trade Terminals with matching name see this village.
        public final Set<String> terminalNames = new LinkedHashSet<>();
        // Cached population/structure count from depot lecterns.
        public int lastPopulation = -1;
        public int lastStructures = -1;
        // Anchor lectern position for refresh queries. Force-loading just this chunk
        // gives fresh data without loading the whole village.
        public @Nullable BlockPos lecternPos = null;
        // Last time this village's stats were live-queried (game tick).
        public long lastRefreshGameTime = Long.MIN_VALUE / 2L;

        public int consumeCap(String category, int requested, int maxCap) {
            int already = capConsumed.getOrDefault(category, 0);
            int remaining = Math.max(0, maxCap - already);
            int consumed = Math.min(requested, remaining);
            if (consumed > 0) {
                capConsumed.put(category, already + consumed);
            }
            return consumed;
        }

        public int getRemainingCap(String category, int maxCap) {
            return Math.max(0, maxCap - capConsumed.getOrDefault(category, 0));
        }

        public void resetCaps(long currentDay) {
            capConsumed.clear();
            lastResetDay = currentDay;
        }
    }

    private final Map<UUID, VillageRecord> villages = new HashMap<>();
    private long lastResetDay = -1L;

    // Global refresh cooldown per terminal name (name → last refresh game tick).
    // Terminals with the same name share the same cooldown.
    private final Map<String, Long> terminalRefreshTimes = new HashMap<>();

    public static VillageCommerceData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        VillageCommerceData::new,
                        (tag, registries) -> load(tag),
                        null
                ),
                DATA_NAME
        );
    }

    public VillageRecord getOrCreateVillage(UUID id) {
        return villages.computeIfAbsent(id, k -> new VillageRecord());
    }

    public boolean hasVillage(UUID id) {
        return villages.containsKey(id);
    }

    public void setProfile(UUID id, String profileName) {
        VillageRecord record = getOrCreateVillage(id);
        record.profileName = profileName;
        setDirty();
    }

    /**
     * Attempts to consume cap for a category. Returns how many items were actually accepted.
     */
    public int consumeCap(UUID villageId, String category, int requested, int maxCap) {
        VillageRecord record = getOrCreateVillage(villageId);
        int consumed = record.consumeCap(category, requested, maxCap);
        if (consumed > 0) setDirty();
        return consumed;
    }

    public int getRemainingCap(UUID villageId, String category, int maxCap) {
        VillageRecord record = getOrCreateVillage(villageId);
        return record.getRemainingCap(category, maxCap);
    }

    public String getProfileName(UUID villageId) {
        VillageRecord record = villages.get(villageId);
        return record != null ? record.profileName : "farming_settlement";
    }

    public String getCustomName(UUID villageId) {
        VillageRecord record = villages.get(villageId);
        return record == null ? "" : (record.customName == null ? "" : record.customName);
    }

    public void setCustomName(UUID villageId, String name) {
        VillageRecord record = getOrCreateVillage(villageId);
        record.customName = name == null ? "" : name;
        setDirty();
    }

    // -- Terminal-name registry --

    /** Caches live population/structure counts for display when bell chunk is unloaded. */
    public void updateVillageStats(UUID villageId, int population, int structures) {
        VillageRecord record = getOrCreateVillage(villageId);
        boolean changed = record.lastPopulation != population || record.lastStructures != structures;
        record.lastPopulation = population;
        record.lastStructures = structures;
        if (changed) setDirty();
    }

    public int getLastPopulation(UUID villageId) {
        VillageRecord record = villages.get(villageId);
        return record == null ? -1 : record.lastPopulation;
    }

    public int getLastStructures(UUID villageId) {
        VillageRecord record = villages.get(villageId);
        return record == null ? -1 : record.lastStructures;
    }

    /** Sets (or clears) the village's refresh anchor lectern position. */
    public void setLecternPos(UUID villageId, @Nullable BlockPos pos) {
        VillageRecord record = getOrCreateVillage(villageId);
        if (!Objects.equals(record.lecternPos, pos)) {
            record.lecternPos = pos == null ? null : pos.immutable();
            setDirty();
        }
    }

    public @Nullable BlockPos getLecternPos(UUID villageId) {
        VillageRecord record = villages.get(villageId);
        return record == null ? null : record.lecternPos;
    }

    public List<String> getTerminalNames(UUID villageId) {
        VillageRecord record = villages.get(villageId);
        if (record == null) return List.of();
        return new ArrayList<>(record.terminalNames);
    }

    public boolean addTerminalName(UUID villageId, String name) {
        if (name == null || name.isBlank()) return false;
        VillageRecord record = getOrCreateVillage(villageId);
        boolean added = record.terminalNames.add(name);
        if (added) setDirty();
        return added;
    }

    public boolean removeTerminalName(UUID villageId, String name) {
        VillageRecord record = villages.get(villageId);
        if (record == null) return false;
        boolean removed = record.terminalNames.remove(name);
        if (removed) setDirty();
        return removed;
    }

    /**
     * Returns the set of village UUIDs whose terminalNames set contains {@code name}.
     */
    public Set<UUID> getVillagesForName(String name) {
        if (name == null || name.isBlank()) return Set.of();
        Set<UUID> out = new HashSet<>();
        for (Map.Entry<UUID, VillageRecord> entry : villages.entrySet()) {
            if (entry.getValue().terminalNames.contains(name)) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    // -- Terminal refresh cooldown --

    /** Returns the game tick when the given terminal name was last refreshed, or MIN_VALUE/2 if never. */
    public long getTerminalRefreshTime(String name) {
        return terminalRefreshTimes.getOrDefault(name, Long.MIN_VALUE / 2L);
    }

    /** Sets the last-refresh tick for a terminal name. */
    public void setTerminalRefreshTime(String name, long gameTick) {
        terminalRefreshTimes.put(name, gameTick);
        setDirty();
    }

    public void resetAllCaps(long currentDay) {
        if (currentDay == lastResetDay) return;
        lastResetDay = currentDay;
        for (VillageRecord record : villages.values()) {
            record.resetCaps(currentDay);
        }
        setDirty();
        LOGGER.debug("[Create: Commerce] Daily caps reset for day {}", currentDay);
    }

    // -- Serialization --

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("LastResetDay", lastResetDay);
        ListTag villageList = new ListTag();
        for (Map.Entry<UUID, VillageRecord> entry : villages.entrySet()) {
            CompoundTag vTag = new CompoundTag();
            vTag.putUUID("UUID", entry.getKey());
            vTag.putString("Profile", entry.getValue().profileName);
            vTag.putString("CustomName", entry.getValue().customName == null ? "" : entry.getValue().customName);
            vTag.putLong("LastResetDay", entry.getValue().lastResetDay);
            CompoundTag caps = new CompoundTag();
            for (Map.Entry<String, Integer> cap : entry.getValue().capConsumed.entrySet()) {
                caps.putInt(cap.getKey(), cap.getValue());
            }
            vTag.put("Caps", caps);
            ListTag nameList = new ListTag();
            for (String name : entry.getValue().terminalNames) {
                nameList.add(StringTag.valueOf(name));
            }
            vTag.put("TerminalNames", nameList);
            vTag.putInt("LastPopulation", entry.getValue().lastPopulation);
            vTag.putInt("LastStructures", entry.getValue().lastStructures);
            if (entry.getValue().lecternPos != null) {
                vTag.putLong("LecternPos", entry.getValue().lecternPos.asLong());
            }
            vTag.putLong("LastRefreshGameTime", entry.getValue().lastRefreshGameTime);
            villageList.add(vTag);
        }
        tag.put("Villages", villageList);

        // Save global terminal refresh cooldowns.
        CompoundTag refreshTag = new CompoundTag();
        for (Map.Entry<String, Long> re : terminalRefreshTimes.entrySet()) {
            refreshTag.putLong(re.getKey(), re.getValue());
        }
        tag.put("TerminalRefreshTimes", refreshTag);
        return tag;
    }

    private static VillageCommerceData load(CompoundTag tag) {
        VillageCommerceData data = new VillageCommerceData();
        data.lastResetDay = tag.getLong("LastResetDay");
        if (tag.contains("Villages", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Villages", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag vTag = list.getCompound(i);
                UUID id = vTag.getUUID("UUID");
                VillageRecord record = new VillageRecord();
                record.profileName = vTag.getString("Profile");
                record.customName = vTag.contains("CustomName") ? vTag.getString("CustomName") : "";
                record.lastResetDay = vTag.getLong("LastResetDay");
                CompoundTag caps = vTag.getCompound("Caps");
                for (String key : caps.getAllKeys()) {
                    record.capConsumed.put(key, caps.getInt(key));
                }
                if (vTag.contains("TerminalNames", Tag.TAG_LIST)) {
                    ListTag nameList = vTag.getList("TerminalNames", Tag.TAG_STRING);
                    for (int j = 0; j < nameList.size(); j++) {
                        record.terminalNames.add(nameList.getString(j));
                    }
                }
                if (vTag.contains("LastPopulation")) record.lastPopulation = vTag.getInt("LastPopulation");
                if (vTag.contains("LastStructures")) record.lastStructures = vTag.getInt("LastStructures");
                if (vTag.contains("LecternPos")) record.lecternPos = BlockPos.of(vTag.getLong("LecternPos"));
                if (vTag.contains("LastRefreshGameTime")) record.lastRefreshGameTime = vTag.getLong("LastRefreshGameTime");
                data.villages.put(id, record);
            }
        }
        // Load global terminal refresh cooldowns.
        if (tag.contains("TerminalRefreshTimes", Tag.TAG_COMPOUND)) {
            CompoundTag refreshTag = tag.getCompound("TerminalRefreshTimes");
            for (String key : refreshTag.getAllKeys()) {
                data.terminalRefreshTimes.put(key, refreshTag.getLong(key));
            }
        }
        return data;
    }
}
