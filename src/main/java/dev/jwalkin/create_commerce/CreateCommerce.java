package dev.jwalkin.create_commerce;

import dev.jwalkin.create_commerce.config.CCConfig;
import dev.jwalkin.create_commerce.config.CoinConverter;
import dev.jwalkin.create_commerce.config.ItemConfigLoader;
import dev.jwalkin.create_commerce.config.VillageConfigLoader;
import dev.jwalkin.create_commerce.data.VillageCommerceData;
import dev.jwalkin.create_commerce.network.AddTerminalNamePacket;
import dev.jwalkin.create_commerce.network.RefreshTerminalPacket;
import dev.jwalkin.create_commerce.network.RemoveTerminalNamePacket;
import dev.jwalkin.create_commerce.network.SetTerminalNamePacket;
import dev.jwalkin.create_commerce.network.SetVillageNamePacket;
import dev.jwalkin.create_commerce.network.UpdateTerminalSnapshotsPacket;
import dev.jwalkin.create_commerce.ponder.CCPonderPlugin;
import dev.jwalkin.create_commerce.registry.CCAttachments;
import dev.jwalkin.create_commerce.registry.CCBlockEntities;
import dev.jwalkin.create_commerce.registry.CCBlocks;
import dev.jwalkin.create_commerce.registry.CCItems;
import dev.jwalkin.create_commerce.registry.CCMenuTypes;
import dev.jwalkin.create_commerce.screen.DepotLecternScreen;
import dev.jwalkin.create_commerce.screen.TradeTerminalScreen;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;

@Mod(CreateCommerce.MOD_ID)
public class CreateCommerce {

    public static final String MOD_ID = "create_commerce";

    // The reset time in game ticks for each option
    private static final long TICK_MIDNIGHT = 18000L;
    private static final long TICK_DAWN = 0L;
    private static final long TICK_NOON = 6000L;
    private static final long TICK_DUSK = 12000L;
    private static final long DAY_TICKS = 24000L;

    public CreateCommerce(IEventBus modEventBus, ModContainer modContainer) {
        CCBlocks.BLOCKS.register(modEventBus);
        CCItems.ITEMS.register(modEventBus);
        CCItems.CREATIVE_MODE_TABS.register(modEventBus);
        CCBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        CCMenuTypes.MENU_TYPES.register(modEventBus);
        CCAttachments.ATTACHMENT_TYPES.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(CCBlockEntities::registerCapabilities);
        modEventBus.addListener(CreateCommerce::registerPayloadHandlers);

        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(ClientEvents::registerScreens);
            modEventBus.addListener(ClientEvents::onClientSetup);
        }

        CCConfig.register(modContainer);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CoinConverter.logRegistryDump();
        });
    }

    private static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MOD_ID).versioned("1.0.0");
        registrar.playToServer(AddTerminalNamePacket.TYPE, AddTerminalNamePacket.STREAM_CODEC, AddTerminalNamePacket::handle);
        registrar.playToServer(RemoveTerminalNamePacket.TYPE, RemoveTerminalNamePacket.STREAM_CODEC, RemoveTerminalNamePacket::handle);
        registrar.playToServer(SetTerminalNamePacket.TYPE, SetTerminalNamePacket.STREAM_CODEC, SetTerminalNamePacket::handle);
        registrar.playToServer(SetVillageNamePacket.TYPE, SetVillageNamePacket.STREAM_CODEC, SetVillageNamePacket::handle);
        registrar.playToServer(RefreshTerminalPacket.TYPE, RefreshTerminalPacket.STREAM_CODEC, RefreshTerminalPacket::handle);
        registrar.playToClient(UpdateTerminalSnapshotsPacket.TYPE, UpdateTerminalSnapshotsPacket.STREAM_CODEC, UpdateTerminalSnapshotsPacket::handle);
    }

    @EventBusSubscriber(modid = MOD_ID)
    public static class GameEvents {

        /** Tracks whether auto-detection still needs to run for the current server. */
        private static boolean autoDetectionPending = false;
        /** Number of ticks to wait before running auto-detection (avoids interfering with server startup). */
        private static final int AUTO_DETECT_DELAY_TICKS = 40; // default 2 seconds
        private static int autoDetectTickCounter = 0;

        @SubscribeEvent
        public static void onServerStarting(ServerStartingEvent event) {
            Path configDir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
            ItemConfigLoader.load(configDir);
            VillageConfigLoader.load(configDir);

            // Schedule auto-detection to run after a short delay
            autoDetectionPending = true;
            autoDetectTickCounter = 0;
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            // Deferred auto-detection: wait a few ticks after server start before
            // scanning all registered items so ServerStartedEvent handlers complete first.
            if (autoDetectionPending) {
                autoDetectTickCounter++;
                if (autoDetectTickCounter >= AUTO_DETECT_DELAY_TICKS) {
                    ItemConfigLoader.runAutoDetection();
                    autoDetectionPending = false;
                }
                return; // Skip other tick logic during warm-up
            }

            // Check every 20 ticks
            ServerLevel overworld = event.getServer().overworld();
            long gameTime = overworld.getDayTime();
            long dayTime = gameTime % DAY_TICKS;

            long resetTick = getResetTick();

            // Fire when the dayTime window opens (within 20 ticks of target)
            if (dayTime >= resetTick && dayTime < resetTick + 20) {
                long currentDay = gameTime / DAY_TICKS;
                VillageCommerceData data = VillageCommerceData.getOrCreate(overworld);
                data.resetAllCaps(currentDay);
            }
        }

        private static long getResetTick() {
            String method = CCConfig.SERVER.resetTimeMethod.get();
            if ("vanilla".equals(method)) {
                return switch (CCConfig.SERVER.vanillaResetTime.get()) {
                    case "dawn" -> TICK_DAWN;
                    case "noon" -> TICK_NOON;
                    case "dusk" -> TICK_DUSK;
                    default -> TICK_MIDNIGHT;
                };
            }
            // Custom: reset every N minutes of real time
            // For now, fall back to midnight
            return TICK_MIDNIGHT;
        }
    }

    public static class ClientEvents {
        @SubscribeEvent
        public static void registerScreens(RegisterMenuScreensEvent event) {
            event.register(CCMenuTypes.DEPOT_LECTERN_MENU.get(), DepotLecternScreen::new);
            event.register(CCMenuTypes.TRADE_TERMINAL_MENU.get(), TradeTerminalScreen::new);
        }

        public static void onClientSetup(FMLClientSetupEvent event) {
            // Plugin registration must happen before Ponder fires FMLLoadCompleteEvent and runs PonderIndex.registerAll().
            event.enqueueWork(() ->
                    net.createmod.ponder.foundation.PonderIndex.addPlugin(new CCPonderPlugin()));
        }
    }
}
