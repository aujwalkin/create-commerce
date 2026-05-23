package dev.jwalkin.create_commerce.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts spur values into Create Numismatics coin stacks.
 * References coins by string ID to avoid compile-time dependency on Numismatics.
 *
 * <p>Denominations: SUN=4096, CROWN=512, COG=64, SPROCKET=16, BEVEL=8, SPUR=1
 */
public final class CoinConverter {

    private static final Logger LOGGER = LogManager.getLogger("create_commerce");

    // Largest-first for greedy conversion
    private static final Denomination[] DENOMINATIONS = {
        new Denomination("sun",      4096),
        new Denomination("crown",     512),
        new Denomination("cog",        64),
        new Denomination("sprocket",   16),
        new Denomination("bevel",       8),
        new Denomination("spur",        1),
    };

    private static final String NUMISMATICS_NS = "numismatics";

    private CoinConverter() {}

    private static @Nullable Item resolveCoinItem(String coinName) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(NUMISMATICS_NS, coinName);
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? null : item;
    }

    /** Spur ItemStack for UI display. */
    public static ItemStack getSpurDisplayStack() {
        Item spur = resolveCoinItem("spur");
        return spur != null ? new ItemStack(spur) : ItemStack.EMPTY;
    }

    public static boolean isNumismaticsLoaded() {
        return resolveCoinItem("spur") != null;
    }

    /** Dumps numismatics items to log for verification. */
    public static void logRegistryDump() {
        LOGGER.info("[Create: Commerce] Numismatics registry dump:");
        boolean found = false;
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation key = entry.getKey().location();
            if (NUMISMATICS_NS.equals(key.getNamespace())) {
                LOGGER.info("[Create: Commerce]   numismatics item: {} → {}", key, entry.getValue().getClass().getSimpleName());
                found = true;
            }
        }
        if (!found) {
            LOGGER.info("[Create: Commerce]   No numismatics items found — mod may not be loaded");
        }
        LOGGER.info("[Create: Commerce] isNumismaticsLoaded() = {}", isNumismaticsLoaded());
    }

    /**
     * Converts spur value to optimal coin stacks, largest first.
     * Overflow carries into smaller denominations. Returns empty list if Numismatics is absent.
     */
    public static List<ItemStack> convertToCoins(int totalSpurs) {
        if (totalSpurs <= 0) return List.of();

        List<ItemStack> result = new ArrayList<>();
        int remaining = totalSpurs;

        LOGGER.info("[Create: Commerce] CoinConverter.convertToCoins({}) called", totalSpurs);

        for (Denomination denom : DENOMINATIONS) {
            if (remaining < denom.spurValue) continue;

            Item coinItem = resolveCoinItem(denom.coinName);
            if (coinItem == null) {
                LOGGER.warn("[Create: Commerce] Could not resolve coin item: numismatics:{} — value {} carried to next denomination", denom.coinName, denom.spurValue);
                continue;
            }

            int count = remaining / denom.spurValue;
            remaining = remaining % denom.spurValue;

            LOGGER.info("[Create: Commerce]   {} x{} (remaining: {})", denom.coinName, count, remaining);

            // Split into stacks of at most maxStackSize (vanilla cap is 64)
            int maxStack = coinItem.getMaxStackSize(new ItemStack(coinItem));
            int fullStacks = count / maxStack;
            int partialCount = count % maxStack;

            for (int i = 0; i < fullStacks; i++) {
                result.add(new ItemStack(coinItem, maxStack));
            }
            if (partialCount > 0) {
                result.add(new ItemStack(coinItem, partialCount));
            }
        }

        return result;
    }

    /** Breakdown of coin name → count, largest first, for display. */
    public static Map<String, Integer> getBreakdown(int totalSpurs) {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        int remaining = totalSpurs;
        for (Denomination denom : DENOMINATIONS) {
            if (remaining < denom.spurValue) continue;
            // Only include in breakdown if the coin item actually resolves
            Item coinItem = resolveCoinItem(denom.coinName);
            if (coinItem == null) continue;
            int count = remaining / denom.spurValue;
            remaining = remaining % denom.spurValue;
            breakdown.put(denom.coinName, count);
        }
        return breakdown;
    }

    private record Denomination(String coinName, int spurValue) {}
}
