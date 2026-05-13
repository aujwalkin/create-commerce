package dev.jwalkin.create_commerce.worldgen;

import dev.jwalkin.create_commerce.CreateCommerce;
import dev.jwalkin.create_commerce.block.DepotLecternBlock;
import dev.jwalkin.create_commerce.blockentity.DepotLecternBlockEntity;
import dev.jwalkin.create_commerce.config.CCConfig;
import dev.jwalkin.create_commerce.data.VillageCommerceData;
import dev.jwalkin.create_commerce.registry.CCBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = CreateCommerce.MOD_ID)
public class DepotLecternWorldGen {

    private static final Logger LOGGER = LogManager.getLogger("create_commerce");
    private static final int SEARCH_RADIUS = 32;
    private static final int PLACEMENT_RADIUS = 24;
    /** Reduced radius for the block-scan fallback when no village record exists. */
    private static final int BLOCK_SCAN_RADIUS = 16;
    /** Max bells to process per server tick from the deferred queue. */
    private static final int MAX_BELLS_PER_TICK = 5;

    /** Deferred bell positions, drained in onServerTick to avoid blocking chunk loads. */
    private static final java.util.Set<BlockPos> PENDING_BELLS = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!CCConfig.SERVER.spawnDepotInEveryVillage.get()) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        try {
            queueChunkBells(level, chunk);
        } catch (Exception e) {
            LOGGER.warn("[Create: Commerce] Error queuing village bells: {}", e.getMessage());
        }
    }

    /** Drains deferred bells and periodically scans for villages near players. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!CCConfig.SERVER.spawnDepotInEveryVillage.get()) return;

        // Drain pending bells every tick (capped at MAX_BELLS_PER_TICK)
        if (!PENDING_BELLS.isEmpty()) {
            int processed = 0;
            var iter = PENDING_BELLS.iterator();
            while (iter.hasNext() && processed < MAX_BELLS_PER_TICK) {
                BlockPos bellPos = iter.next();
                iter.remove();
                processed++;
                try {
                    ServerLevel level = event.getServer().overworld();
                    handleVillageBell(level, bellPos);
                } catch (Exception e) {
                    LOGGER.warn("[Create: Commerce] Error processing deferred bell at {}: {}", bellPos, e.getMessage());
                }
            }
            if (processed > 0 && CCConfig.SERVER.logLecternSpawns.get()) {
                LOGGER.debug("[Create: Commerce] Processed {}/{} deferred bells this tick (remaining: {})",
                        processed, MAX_BELLS_PER_TICK, PENDING_BELLS.size());
            }
        }

        // Periodic scan every 200 ticks — safety net for missed chunk events
        if (event.getServer().getTickCount() % 200 != 0) return;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (var player : level.players()) {
                try {
                    scanAround(level, player.blockPosition());
                } catch (Exception e) {
                    LOGGER.warn("[Create: Commerce] Error during periodic village scan: {}", e.getMessage());
                }
            }
        }
    }

    private static void scanAround(ServerLevel level, BlockPos centre) {
        var poiManager = level.getPoiManager();
        poiManager.getInRange(
                holder -> holder.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING),
                centre, 128,
                net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY
        ).forEach(poi -> {
            try {
                handleVillageBell(level, poi.getPos());
            } catch (Exception e) {
                LOGGER.warn("[Create: Commerce] Error placing depot at {}: {}", poi.getPos(), e.getMessage());
            }
        });
    }

    /** Queues MEETING POIs found in a chunk for deferred processing. */
    private static void queueChunkBells(ServerLevel level, LevelChunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        var poiManager = level.getPoiManager();

        poiManager.getInRange(
                holder -> holder.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING),
                chunkPos.getMiddleBlockPosition(64),
                SEARCH_RADIUS,
                net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY
        ).forEach(poi -> {
            BlockPos bellPos = poi.getPos();
            // Avoid recursive chunk loading inside ChunkEvent.Load.
            ChunkPos bellChunk = new ChunkPos(bellPos);
            if (!bellChunk.equals(chunkPos)) return;

            // Skip if village already registered
            UUID villageId = generateVillageUUID(bellPos);
            VillageCommerceData data = VillageCommerceData.getOrCreate(level);
            if (data.hasVillage(villageId)) return;

            PENDING_BELLS.add(bellPos.immutable());
            if (CCConfig.SERVER.logLecternSpawns.get()) {
                LOGGER.debug("[Create: Commerce] Queued village bell at {} for deferred processing", bellPos);
            }
        });
    }

    private static void handleVillageBell(ServerLevel level, BlockPos bellPos) {
        // Check if a Depot Lectern already exists nearby
        if (hasDepotLecternNearby(level, bellPos)) return;

        // Guarantee 1 depot per village: regular search first, forced fallback above bell.
        BlockPos placeAt = findPlacementPos(level, bellPos)
                .orElseGet(() -> forcedFallbackPlacement(level, bellPos));

        // Generate a stable UUID for this village based on the bell position
        UUID villageId = generateVillageUUID(bellPos);

        // Place the block
        BlockState state = CCBlocks.DEPOT_LECTERN.get().defaultBlockState()
                .setValue(DepotLecternBlock.FACING, Direction.SOUTH);
        level.setBlock(placeAt, state, 3);

        // Set the village UUID on the block entity
        BlockEntity be = level.getBlockEntity(placeAt);
        if (be instanceof DepotLecternBlockEntity lectern) {
            lectern.setVillageUUID(villageId);
            lectern.setChanged();
        }

        // Register village in SavedData
        VillageCommerceData data = VillageCommerceData.getOrCreate(level);
        if (!data.hasVillage(villageId)) {
            data.getOrCreateVillage(villageId);
            data.setProfile(villageId, assignProfile(level, bellPos));
            String defaultName = dev.jwalkin.create_commerce.compat.VillageNameProvider
                    .getDefaultName(level, bellPos);
            if (!defaultName.isBlank()) {
                data.setCustomName(villageId, defaultName);
            }
        }

        if (CCConfig.SERVER.logLecternSpawns.get()) {
            LOGGER.info("[Create: Commerce] Placed Depot Lectern at {} (village UUID: {})",
                    placeAt, villageId);
        }
    }

    /**
     * O(1) village-registry check first; falls back to block scan only when no record exists.
     */
    private static boolean hasDepotLecternNearby(ServerLevel level, BlockPos centre) {
        // O(1): village already registered → lectern was placed
        UUID villageId = generateVillageUUID(centre);
        VillageCommerceData data = VillageCommerceData.getOrCreate(level);
        if (data.hasVillage(villageId)) return true;

        // Slow-path: reduced-radius block scan
        int r = BLOCK_SCAN_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(
                centre.offset(-r, -8, -r),
                centre.offset(r, 8, r))) {
            if (level.getBlockState(pos).is(CCBlocks.DEPOT_LECTERN.get())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<BlockPos> findPlacementPos(ServerLevel level, BlockPos centre) {
        for (int r = 2; r <= PLACEMENT_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    BlockPos candidate = centre.offset(dx, 0, dz);
                    if (isValidPlacement(level, candidate)) return Optional.of(candidate);
                    // Try one block up and down
                    if (isValidPlacement(level, candidate.above())) return Optional.of(candidate.above());
                    if (isValidPlacement(level, candidate.below())) return Optional.of(candidate.below());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Last-ditch placement: tries cardinal neighbours + above the bell.
     * Always returns a position so every village gets a depot.
     */
    private static BlockPos forcedFallbackPlacement(ServerLevel level, BlockPos bellPos) {
        BlockPos[] candidates = {
                bellPos.north(), bellPos.south(), bellPos.east(), bellPos.west(),
                bellPos.north().above(), bellPos.south().above(),
                bellPos.east().above(), bellPos.west().above(),
                bellPos.above()
        };

        for (BlockPos candidate : candidates) {
            if (!level.isLoaded(candidate)) continue;
            BlockState at = level.getBlockState(candidate);
            if (level.isWaterAt(candidate)) continue;
            if (at.is(net.minecraft.world.level.block.Blocks.BEDROCK)) continue;
            return candidate;
        }
        return bellPos.above();
    }

    private static boolean isValidPlacement(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState below = level.getBlockState(pos.below());
        BlockState at = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        // Allow placing into air or replaceable blocks (tall grass, flowers, snow).
        boolean atOk = at.isAir() || at.canBeReplaced();
        boolean aboveOk = above.isAir() || above.canBeReplaced();
        return below.isFaceSturdy(level, pos.below(), Direction.UP)
                && atOk
                && aboveOk
                && !level.isWaterAt(pos);
    }

    public static UUID generateVillageUUID(BlockPos bellPos) {
        // Deterministic UUID from bell position
        long most = ((long) bellPos.getX() << 32) | (bellPos.getZ() & 0xFFFFFFFFL);
        long least = bellPos.getY();
        return new UUID(most, least);
    }

    public static String assignProfile(ServerLevel level, BlockPos bellPos) {
        // Check biome override first
        var biomeHolder = level.getBiome(bellPos);
        String biomeId = biomeHolder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("");
        String biomeProfile = dev.jwalkin.create_commerce.config.VillageConfigLoader
                .getProfileForBiome(biomeId);
        if (biomeProfile != null) return biomeProfile;

        // Weighted random from profile weights
        var weights = dev.jwalkin.create_commerce.config.VillageConfigLoader.getProfileWeights();
        if (weights.isEmpty()) return "farming_settlement";

        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int roll = level.random.nextInt(total);
        int cumulative = 0;
        for (var entry : weights.entrySet()) {
            cumulative += entry.getValue();
            if (roll < cumulative) return entry.getKey();
        }
        return "farming_settlement";
    }
}
