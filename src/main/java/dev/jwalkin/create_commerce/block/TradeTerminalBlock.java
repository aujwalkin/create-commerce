package dev.jwalkin.create_commerce.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.jwalkin.create_commerce.blockentity.TradeTerminalBlockEntity;
import dev.jwalkin.create_commerce.config.CCConfig;
import dev.jwalkin.create_commerce.data.VillageCommerceData;
import dev.jwalkin.create_commerce.data.VillageSnapshot;
import dev.jwalkin.create_commerce.registry.CCBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TradeTerminalBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<TradeTerminalBlock> CODEC = simpleCodec(TradeTerminalBlock::new);

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    @Override
    public MapCodec<TradeTerminalBlock> codec() {
        return CODEC;
    }

    public TradeTerminalBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(@Nonnull StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(@Nonnull BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(@Nonnull BlockPos pos, @Nonnull BlockState state) {
        return new TradeTerminalBlockEntity(pos, state);
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;

        // Create-consistent wrench pickup.
        level.destroyBlock(pos, false, serverPlayer);

        ItemStack blockItem = new ItemStack(CCBlocks.TRADE_TERMINAL.get());
        if (!serverPlayer.addItem(blockItem)) {
            level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                    level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, blockItem));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull Player player, @Nonnull BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TradeTerminalBlockEntity terminal)) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;

        String name = terminal.getTerminalName();
        List<VillageSnapshot> snapshots = buildSnapshotsByName(name, (ServerLevel) level, pos);

        // Compute cooldown remaining for this terminal name.
        final long cooldownRemaining;
        if (name != null && !name.isBlank()) {
            VillageCommerceData data = VillageCommerceData.getOrCreate((ServerLevel) level);
            long lastRefresh = data.getTerminalRefreshTime(name);
            long elapsed = level.getGameTime() - lastRefresh;
            cooldownRemaining = Math.max(0L, TradeTerminalBlockEntity.REFRESH_COOLDOWN_TICKS - elapsed);
        } else {
            cooldownRemaining = 0L;
        }

        serverPlayer.openMenu(terminal, buf -> {
            buf.writeBlockPos(pos);
            buf.writeUtf(name == null ? "" : name, 64);
            buf.writeVarInt(snapshots.size());
            for (VillageSnapshot snap : snapshots) {
                VillageSnapshot.STREAM_CODEC.encode(buf, snap);
            }
            buf.writeVarLong(cooldownRemaining);
        });
        return InteractionResult.CONSUME;
    }

    @Override
    public RenderShape getRenderShape(@Nonnull BlockState state) {
        return RenderShape.MODEL;
    }

    public static List<VillageSnapshot> buildSnapshotsByName(String name, ServerLevel level, BlockPos terminalPos) {
        if (name == null || name.isBlank()) return List.of();
        VillageCommerceData data = VillageCommerceData.getOrCreate(level);
        Set<UUID> villageIds = data.getVillagesForName(name);
        if (villageIds.isEmpty()) return List.of();

        int defaultCap = CCConfig.getCapForSize(10);
        List<VillageSnapshot> snapshots = new ArrayList<>();

        for (UUID villageId : villageIds) {
            String profileId = data.getProfileName(villageId);
            String profileName = formatProfileName(profileId);
            String customName = data.getCustomName(villageId);
            // Resolve area name from RVN/Areas if no custom name
            if (customName == null || customName.isBlank()) {
                BlockPos bp = bellPosFromUUID(villageId);
                String areaName = dev.jwalkin.create_commerce.compat.VillageNameProvider
                        .getDefaultName(level, bp);
                if (!areaName.isBlank()) {
                    customName = areaName;
                }
            }
            VillageCommerceData.VillageRecord record = data.getOrCreateVillage(villageId);

            // Resolve profile lists into concrete items + payouts for the client.
            List<String> accepts = List.of();
            List<String> filteredAccepts = new ArrayList<>();
            List<Integer> acceptInputs = new ArrayList<>();
            List<Integer> acceptPayouts = new ArrayList<>();
            List<Integer> acceptConsumed = new ArrayList<>();
            List<Integer> acceptCapMax = new ArrayList<>();
            List<String> bonus = List.of();
            List<String> refuses = List.of();
            dev.jwalkin.create_commerce.config.VillageConfigLoader.VillageProfile profile =
                    dev.jwalkin.create_commerce.config.VillageConfigLoader.getProfile(profileId);
            if (profile != null) {
                accepts = resolveTagsToItems(level, profile.accepts());
                bonus = resolveTagsToItems(level, profile.bonusAccepts());
                refuses = resolveTagsToItems(level, profile.refuses());
                for (String id : accepts) {
                    net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .get(net.minecraft.resources.ResourceLocation.parse(id));
                    net.minecraft.world.item.ItemStack probe = new net.minecraft.world.item.ItemStack(item);
                    int payout = dev.jwalkin.create_commerce.config.ItemConfigLoader.getValueInEmeralds(probe);
                    if (payout <= 0) continue; // skip items with no pricing
                    filteredAccepts.add(id);
                    acceptInputs.add(dev.jwalkin.create_commerce.config.ItemConfigLoader.getInputAmount(probe));
                    acceptPayouts.add(payout);
                    int itemCap = dev.jwalkin.create_commerce.config.ItemConfigLoader.getDailyCap(probe);
                    int resolvedMax = itemCap < 0 ? defaultCap : itemCap;
                    acceptCapMax.add(resolvedMax);
                    acceptConsumed.add(record.capConsumed.getOrDefault(id, 0));
                }
            }

            // Aggregate per-item caps into a single bar for the village-list row.
            int aggConsumed = 0;
            int aggMax = 0;
            for (int i = 0; i < acceptConsumed.size(); i++) {
                aggConsumed += acceptConsumed.get(i);
                aggMax += acceptCapMax.get(i);
            }
            Map<String, Integer> consumed = new HashMap<>();
            Map<String, Integer> max = new HashMap<>();
            consumed.put("general", aggConsumed);
            max.put("general", Math.max(aggMax, 1));

            List<String> terminalNames = data.getTerminalNames(villageId);

            // Population + structures: live count if bell chunk loaded, otherwise cached.
            BlockPos bellPos = bellPosFromUUID(villageId);
            int villagerCount = -1;
            int structureCount = -1;
            if (level.isLoaded(bellPos)) {
                int range = 48;
                villagerCount = level.getEntitiesOfClass(
                        net.minecraft.world.entity.npc.Villager.class,
                        new net.minecraft.world.phys.AABB(
                                bellPos.getX() - range, bellPos.getY() - range, bellPos.getZ() - range,
                                bellPos.getX() + range, bellPos.getY() + range, bellPos.getZ() + range)
                ).size();
                structureCount = (int) level.getPoiManager().getInRange(
                        holder -> holder.is(net.minecraft.tags.PoiTypeTags.VILLAGE),
                        bellPos, range,
                        net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY
                ).count();
                // Refresh cache for other terminals
                data.updateVillageStats(villageId, villagerCount, structureCount);
            } else {
                villagerCount = data.getLastPopulation(villageId);
                structureCount = data.getLastStructures(villageId);
            }

            snapshots.add(new VillageSnapshot(villageId, customName, profileName, consumed, max,
                    structureCount, filteredAccepts, acceptInputs, acceptPayouts,
                    acceptConsumed, acceptCapMax,
                    bonus, refuses, terminalNames, villagerCount));
        }
        return snapshots;
    }

    /** Inverse of DepotLecternWorldGen.generateVillageUUID, returns bell pos. */
    private static BlockPos bellPosFromUUID(UUID id) {
        long most = id.getMostSignificantBits();
        int x = (int) (most >> 32);
        int z = (int) (most & 0xFFFFFFFFL);
        int y = (int) id.getLeastSignificantBits();
        return new BlockPos(x, y, z);
    }

    /** Expands tag entries (#...) into concrete item IDs. */
    private static List<String> resolveTagsToItems(ServerLevel level, List<String> entries) {
        var registry = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.ITEM);
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            if (entry.startsWith("#")) {
                try {
                    net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tagKey =
                            net.minecraft.tags.TagKey.create(
                                    net.minecraft.core.registries.Registries.ITEM,
                                    net.minecraft.resources.ResourceLocation.parse(entry.substring(1)));
                    registry.getTag(tagKey).ifPresent(holderSet -> holderSet.forEach(holder ->
                            holder.unwrapKey().ifPresent(k -> result.add(k.location().toString()))));
                } catch (Exception ignored) {
                }
            } else {
                result.add(entry);
            }
        }
        return new ArrayList<>(result);
    }

    private static String formatProfileName(String raw) {
        if (raw == null || raw.isEmpty()) return "Unknown Village";
        String[] parts = raw.split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
