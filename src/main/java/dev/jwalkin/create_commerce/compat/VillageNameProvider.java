package dev.jwalkin.create_commerce.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/** Reads RVN/Areas signs near the bell to resolve a default village name. */
public final class VillageNameProvider {

    private static final Logger LOGGER = LogManager.getLogger("create_commerce");

    /** Areas mod zone prefixes. */
    private static final List<String> ZONE_PREFIXES = List.of(
            "[na]", "[area]", "[region]", "[zone]"
    );

    private static final int SIGN_SEARCH_RADIUS = 15;

    // Cached once per JVM.
    private static volatile Boolean rvnAvailable = null;

    private VillageNameProvider() {}

    /** Returns the default name for a village, or empty string if none found. */
    public static String getDefaultName(ServerLevel level, BlockPos bellPos) {
        String fromRvn = tryRandomVillageNames(level, bellPos);
        if (fromRvn != null && !fromRvn.isBlank()) return fromRvn;
        return "";
    }

    /** Scans for Areas-format sign near bell and extracts the village name. */
    private static String tryRandomVillageNames(ServerLevel level, BlockPos bellPos) {
        if (!isRvnLoaded()) return null;

        try {
            int r = SIGN_SEARCH_RADIUS;
            for (BlockPos pos : BlockPos.betweenClosed(
                    bellPos.offset(-r, -5, -r),
                    bellPos.offset(r, 5, r))) {

                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof SignBlockEntity sign)) continue;

                Component[] lines = sign.getFrontText().getMessages(false);

                // Line 0 must start with a zone prefix to be an Areas sign
                String line0 = lines[0].getString().trim().toLowerCase();
                if (!hasZonePrefix(line0)) continue;

                // Extract the name from lines 1-3, skipping directives and empties
                StringBuilder name = new StringBuilder();
                for (int i = 1; i < lines.length; i++) {
                    String text = lines[i].getString().trim();
                    if (text.isEmpty()) continue;
                    String lower = text.toLowerCase();
                    // Skip RGB directives like "[RGB] #rrggbb"
                    if (lower.startsWith("[rgb]")) continue;
                    // Skip any secondary zone prefix lines
                    if (hasZonePrefix(lower)) continue;
                    // Skip lines that look like radius-only (e.g. "60")
                    if (isRadiusOnly(lower)) continue;

                    if (!name.isEmpty()) name.append(" ");
                    name.append(text);
                }

                if (!name.isEmpty()) {
                    LOGGER.debug("[Create: Commerce] Found RVN/Areas village name '{}' at sign near bell {}",
                            name, bellPos);
                    return name.toString();
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[Create: Commerce] RVN sign scan failed: {}", e.getMessage());
        }
        return null;
    }

    private static boolean hasZonePrefix(String lowerLine) {
        for (String prefix : ZONE_PREFIXES) {
            if (lowerLine.startsWith(prefix)) return true;
        }
        return false;
    }

    /** True if the line is purely digits. */
    private static boolean isRadiusOnly(String lowerLine) {
        for (int i = 0; i < lowerLine.length(); i++) {
            if (!Character.isDigit(lowerLine.charAt(i))) return false;
        }
        return !lowerLine.isEmpty();
    }

    private static boolean isRvnLoaded() {
        Boolean cached = rvnAvailable;
        if (cached != null) return cached;
        boolean loaded = ModList.get().isLoaded("randomvillagenames");
        rvnAvailable = loaded;
        return loaded;
    }
}
