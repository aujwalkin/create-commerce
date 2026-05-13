package dev.jwalkin.create_commerce.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CCConfig {

    private static final Logger LOGGER = LogManager.getLogger("create_commerce");

    public static final CCServerConfig SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SERVER = new CCServerConfig(builder);
        SERVER_SPEC = builder.build();
    }

    private static final String CONFIG_FOLDER = "create_commerce";
    private static final String CONFIG_FILE = "create_commerce-server.toml";

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC, CONFIG_FOLDER + "/" + CONFIG_FILE);
    }

    public static ItemStack getCurrencyItem() {
        String itemId = SERVER.currencyItem.get();
        try {
            ResourceLocation loc = ResourceLocation.parse(itemId);
            Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(loc);
            if (item == Items.AIR) {
                LOGGER.warn("[Create: Commerce] currency_item '{}' is not a valid item ID, falling back to minecraft:emerald", itemId);
                return new ItemStack(Items.EMERALD);
            }
            return new ItemStack(item);
        } catch (Exception e) {
            LOGGER.warn("[Create: Commerce] Failed to parse currency_item '{}', falling back to minecraft:emerald: {}", itemId, e.getMessage());
            return new ItemStack(Items.EMERALD);
        }
    }

    public static int getCapForSize(int buildingCount) {
        int base;
        if (buildingCount < 5) {
            base = SERVER.hamletCap.get();
        } else if (buildingCount <= 12) {
            base = SERVER.villageCap.get();
        } else if (buildingCount <= 20) {
            base = SERVER.townCap.get();
        } else {
            base = SERVER.cityCap.get();
        }
        double multiplied = base * SERVER.globalCapMultiplier.get();
        return (int) Math.round(multiplied);
    }
}
