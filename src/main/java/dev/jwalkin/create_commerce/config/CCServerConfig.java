package dev.jwalkin.create_commerce.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class CCServerConfig {

    public final ModConfigSpec.BooleanValue spawnDepotInEveryVillage;
    public final ModConfigSpec.IntValue minDistanceBetweenDepots;
    public final ModConfigSpec.ConfigValue<String> resetTimeMethod;
    public final ModConfigSpec.ConfigValue<String> vanillaResetTime;
    public final ModConfigSpec.IntValue customResetTime;
    public final ModConfigSpec.BooleanValue showFloatingDemandIcon;

    public final ModConfigSpec.DoubleValue globalPayoutMultiplier;
    public final ModConfigSpec.DoubleValue globalCapMultiplier;
    public final ModConfigSpec.BooleanValue enforceDepositCooldown;
    public final ModConfigSpec.IntValue depositCooldownSeconds;
    public final ModConfigSpec.ConfigValue<String> currencyItem;
    public final ModConfigSpec.BooleanValue allowHandDeposit;
    public final ModConfigSpec.IntValue maxLinkedVillagesPerPlayer;
    public final ModConfigSpec.BooleanValue enableAutoDetection;

    public final ModConfigSpec.IntValue hamletCap;
    public final ModConfigSpec.IntValue villageCap;
    public final ModConfigSpec.IntValue townCap;
    public final ModConfigSpec.IntValue cityCap;
    public final ModConfigSpec.BooleanValue villagersAffectCap;
    public final ModConfigSpec.ConfigValue<String> villagerCapType;
    public final ModConfigSpec.DoubleValue villagerCapCustom;

    public final ModConfigSpec.BooleanValue compatFarmersDelight;
    public final ModConfigSpec.BooleanValue compatPamsHarvestcraft;
    public final ModConfigSpec.BooleanValue compatCreateBigCannons;
    public final ModConfigSpec.BooleanValue compatMekanism;
    public final ModConfigSpec.BooleanValue compatPointblank;
    public final ModConfigSpec.BooleanValue compatCreateAeronautics;
    public final ModConfigSpec.BooleanValue compatSteamNRails;

    public final ModConfigSpec.BooleanValue logDeposits;
    public final ModConfigSpec.BooleanValue logLecternSpawns;

    CCServerConfig(ModConfigSpec.Builder builder) {
        builder.comment(
                "Create: Commerce — Server Config",
                "Edit to adjust world behaviour and economy rules.",
                "Changes take effect on world reload (run /reload)."
        ).push("world");

        spawnDepotInEveryVillage = builder
                .comment("Should a Depot Lectern be generated in every village?",
                        "Set to false if you want to manually control which villages have depots.",
                        "Default: true")
                .define("spawn_depot_in_every_village", true);

        minDistanceBetweenDepots = builder
                .comment("Minimum number of blocks between two Depot Lecterns.",
                        "Set to 0 to disable the check.",
                        "Default: 0")
                .defineInRange("min_distance_between_depots", 0, 0, 100000);

        resetTimeMethod = builder
                .comment("How should daily caps reset?",
                        "Options: \"vanilla\" | \"custom\"",
                        "  vanilla = resets at a specific in-game time of day",
                        "  custom  = resets every X real-time minutes",
                        "Default: \"vanilla\"")
                .define("reset_time_method", "vanilla");

        vanillaResetTime = builder
                .comment("When should caps reset? Only applies if reset_time_method = \"vanilla\".",
                        "Options: \"midnight\" | \"dawn\" | \"noon\" | \"dusk\"",
                        "Default: \"midnight\"")
                .define("vanilla_reset_time", "midnight");

        customResetTime = builder
                .comment("Duration of custom cap reset in minutes.",
                        "Only applies if reset_time_method = \"custom\".",
                        "Range: 1–525600. Default: 1440 (24 hours)")
                .defineInRange("custom_reset_time", 1440, 1, 525600);

        showFloatingDemandIcon = builder
                .comment("Show a floating particle/icon above the Depot Lectern",
                        "showing what the village currently wants most?",
                        "Default: true")
                .define("show_floating_demand_icon", true);

        builder.pop().push("economy");

        globalPayoutMultiplier = builder
                .comment("Global multiplier applied to ALL item payout values.",
                        "Range: 0.01–100.0. Example: 2.0 = double all payouts.",
                        "Default: 1.0")
                .defineInRange("global_payout_multiplier", 1.0, 0.01, 100.0);

        globalCapMultiplier = builder
                .comment("Global multiplier applied to ALL daily caps.",
                        "Useful for adjusting the economy for larger or smaller servers.",
                        "Example: 4.0 on a 10-player server gives each player roughly",
                        "the same throughput as a 2-player server at 2.0.",
                        "Default: 1.0")
                .defineInRange("global_cap_multiplier", 1.0, 0.01, 100.0);

        enforceDepositCooldown = builder
                .comment("Enforce a cooldown between deposits at the same Depot Lectern?",
                        "Prevents spam-clicking. Disable only for high-throughput automated setups.",
                        "Default: true")
                .define("enforce_deposit_cooldown", true);

        depositCooldownSeconds = builder
                .comment("Seconds between deposits at the same Depot Lectern.",
                        "Only applies if enforce_deposit_cooldown = true.",
                        "Minimum: 1. Default: 1")
                .defineInRange("deposit_cooldown_seconds", 1, 1, 3600);

        currencyItem = builder
                .comment("What item should be paid out as currency?",
                        "Use any valid item ID. Examples: \"numismatics:spur\", \"minecraft:emerald\", \"minecraft:gold_nugget\"",
                        "If the item ID is invalid, falls back to \"minecraft:emerald\" with a warning.",
                        "Default: \"numismatics:spur\" (Create Numismatics spur coin)")
                .define("currency_item", "numismatics:spur");

        allowHandDeposit = builder
                .comment("Allow players to deposit items by right-clicking the Depot Lectern directly?",
                        "Useful for early-game manual delivery. Default: true")
                .define("allow_hand_deposit", true);

        maxLinkedVillagesPerPlayer = builder
                .comment("Maximum villages each player can link to their Trade Terminal.",
                        "Set to -1 for unlimited. Default: -1")
                .defineInRange("max_linked_villages_per_player", -1, -1, 10000);

        enableAutoDetection = builder
                .comment("Auto-detect values for modded items not in the config?",
                        "When enabled, items from other mods are automatically priced",
                        "based on their properties: food (nutrition), tools/armor (tier),",
                        "and materials (name patterns like _ingot, _gem, _dust, etc.).",
                        "Auto-detected values always lose to item_overrides and tag_tiers.",
                        "Set to false if you want full manual control over all pricing.",
                        "Default: true")
                .define("enable_auto_detection", true);

        builder.pop().push("caps");

        builder.comment(
                "Daily cap (per item category) for each village size tier.",
                "These are baseline values before global_cap_multiplier is applied.",
                "  hamlet  = fewer than 5 buildings",
                "  village = 5–12 buildings",
                "  town    = 13–20 buildings",
                "  city    = 21+ buildings (modded large villages)",
                "Default values are calibrated for Create industrial-scale automation."
        );

        hamletCap = builder
                .comment("Daily cap for hamlet-sized villages. Default: 256")
                .defineInRange("hamlet_cap", 256, 1, 1000000);

        villageCap = builder
                .comment("Daily cap for standard villages. Default: 512")
                .defineInRange("village_cap", 512, 1, 1000000);

        townCap = builder
                .comment("Daily cap for town-sized villages. Default: 1024")
                .defineInRange("town_cap", 1024, 1, 1000000);

        cityCap = builder
                .comment("Daily cap for city-sized villages (modded). Default: 2048")
                .defineInRange("city_cap", 2048, 1, 1000000);

        villagersAffectCap = builder
                .comment("Should villager count influence daily caps?",
                        "Encourages keeping your customers alive and well-lit.",
                        "Default: true")
                .define("villagers_affect_cap", true);

        villagerCapType = builder
                .comment("How should the villager multiplier work?",
                        "Options: \"scaled\" | \"custom\"",
                        "  scaled = cap scales with ratio of villagers to buildings, max 2×.",
                        "  custom = same as scaled, but uses villager_cap_custom as the max multiplier.",
                        "Default: \"scaled\"")
                .define("villager_cap_type", "scaled");

        villagerCapCustom = builder
                .comment("Maximum cap multiplier when villager_cap_type = \"custom\".",
                        "Example: 3.0 means a fully-populated village earns up to 3× the base cap.",
                        "Default: 2.0")
                .defineInRange("villager_cap_custom", 2.0, 0.1, 100.0);

        builder.pop().push("compat");

        builder.comment(
                "Toggle mod compatibility packs on or off.",
                "When enabled AND that mod is installed, its items are automatically",
                "assigned to their default tier values from create_commerce-items.json.",
                "You do not need the mod installed — this simply has no effect if the mod is absent."
        );

        compatFarmersDelight = builder
                .define("farmers_delight", true);
        compatPamsHarvestcraft = builder
                .define("pams_harvestcraft", true);
        compatCreateBigCannons = builder
                .define("create_big_cannons", true);
        compatMekanism = builder
                .define("mekanism", true);
        compatPointblank = builder
                .define("pointblank", true);
        compatCreateAeronautics = builder
                .comment("Docking integration (requires Aeronautics)")
                .define("create_aeronautics", true);
        compatSteamNRails = builder
                .comment("Train schedule integration (requires railways)")
                .define("steam_n_rails", true);

        builder.pop().push("debug");

        logDeposits = builder
                .comment("Print a log message every time an item is deposited at a depot.",
                        "Useful for debugging config issues. Default: false")
                .define("log_deposits", false);

        logLecternSpawns = builder
                .comment("Print a log message when a Depot Lectern spawns in a new village.",
                        "Default: false")
                .define("log_lectern_spawns", false);

        builder.pop();
    }
}
