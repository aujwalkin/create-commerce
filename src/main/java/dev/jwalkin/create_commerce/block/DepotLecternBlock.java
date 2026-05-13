package dev.jwalkin.create_commerce.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.jwalkin.create_commerce.blockentity.DepotLecternBlockEntity;
import dev.jwalkin.create_commerce.data.VillageCommerceData;
import dev.jwalkin.create_commerce.registry.CCBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import javax.annotation.Nonnull;

import java.util.UUID;

public class DepotLecternBlock extends BaseEntityBlock implements IWrenchable {

    public static final MapCodec<DepotLecternBlock> CODEC = simpleCodec(DepotLecternBlock::new);

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    // Lectern-shaped collision/occlusion.
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(0.0D, 10.0D, 0.0D, 16.0D, 14.0D, 16.0D),
            Block.box(4.0D, 0.0D, 4.0D, 12.0D, 14.0D, 12.0D)
    );

    @Override
    public MapCodec<DepotLecternBlock> codec() {
        return CODEC;
    }

    public DepotLecternBlock(BlockBehaviour.Properties properties) {
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
        return new DepotLecternBlockEntity(pos, state);
    }

    @Override
    public @Nonnull VoxelShape getShape(@Nonnull BlockState state, @Nonnull BlockGetter level,
                                        @Nonnull BlockPos pos, @Nonnull CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nonnull VoxelShape getCollisionShape(@Nonnull BlockState state, @Nonnull BlockGetter level,
                                                 @Nonnull BlockPos pos, @Nonnull CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nonnull VoxelShape getOcclusionShape(@Nonnull BlockState state, @Nonnull BlockGetter level,
                                                 @Nonnull BlockPos pos) {
        return SHAPE;
    }

    @Override
    public boolean useShapeForLightOcclusion(@Nonnull BlockState state) {
        return true;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof DepotLecternBlockEntity lectern) {
            dropContents(level, pos, lectern.getInputHandler());
            dropContents(level, pos, lectern.getOutputCapability());

            // Clear the village's anchor lectern so another can claim it.
            UUID villageId = lectern.getVillageUUID();
            if (villageId != null && level instanceof ServerLevel sl) {
                VillageCommerceData data = VillageCommerceData.getOrCreate(sl);
                BlockPos stored = data.getLecternPos(villageId);
                if (pos.equals(stored)) {
                    data.setLecternPos(villageId, null);
                }
            }
        }

        // Create-consistent wrench pickup.
        level.destroyBlock(pos, false, serverPlayer);

        ItemStack blockItem = new ItemStack(CCBlocks.DEPOT_LECTERN.get());
        if (!serverPlayer.addItem(blockItem)) {
            level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                    level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, blockItem));
        }
        return InteractionResult.SUCCESS;
    }

    private static void dropContents(Level level, BlockPos pos, IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.extractItem(i, Integer.MAX_VALUE, false);
            if (!stack.isEmpty()) {
                level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                        level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull Player player, @Nonnull BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof DepotLecternBlockEntity lectern)) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.SUCCESS;

        java.util.List<String> names = java.util.List.of();
        java.util.List<String> accepts = java.util.List.of();
        java.util.List<String> bonusAccepts = java.util.List.of();
        String customName = "";
        String profileDisplay = "Unknown Village";
        UUID villageId = lectern.getVillageUUID();
        if (villageId != null) {
            VillageCommerceData data = VillageCommerceData.getOrCreate(serverLevel);
            names = data.getTerminalNames(villageId);
            customName = data.getCustomName(villageId);
            String profileId = data.getProfileName(villageId);
            dev.jwalkin.create_commerce.config.VillageConfigLoader.VillageProfile profile =
                    dev.jwalkin.create_commerce.config.VillageConfigLoader.getProfile(profileId);
            if (profile != null) {
                // Resolve tag entries (#c:tools/swords) into concrete item IDs so the
                // client UI can list every actual accepted item with proper names + icons.
                accepts = resolveTagsToItems(serverLevel, profile.accepts());
                bonusAccepts = resolveTagsToItems(serverLevel, profile.bonusAccepts());
                profileDisplay = profile.displayName();
            }
        }

        // Compute input_amount + payout on the server for the client UI.
        java.util.List<Integer> acceptInputs = new java.util.ArrayList<>();
        java.util.List<Integer> acceptPayouts = new java.util.ArrayList<>();
        java.util.List<String> filteredAccepts = new java.util.ArrayList<>();
        for (String id : accepts) {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.parse(id));
            net.minecraft.world.item.ItemStack probe = new net.minecraft.world.item.ItemStack(item);
            int payout = dev.jwalkin.create_commerce.config.ItemConfigLoader.getValueInEmeralds(probe);
            if (payout <= 0) continue;
            filteredAccepts.add(id);
            acceptInputs.add(dev.jwalkin.create_commerce.config.ItemConfigLoader.getInputAmount(probe));
            acceptPayouts.add(payout);
        }

        final java.util.List<String> finalNames = names;
        final java.util.List<String> finalAccepts = filteredAccepts;
        final java.util.List<Integer> finalInputs = acceptInputs;
        final java.util.List<Integer> finalPayouts = acceptPayouts;
        final java.util.List<String> finalBonus = bonusAccepts;
        // Try to resolve an area name from RVN/Areas signs if no custom name is set.
        String resolvedName = (customName == null || customName.isBlank()) ? "" : customName;
        if (resolvedName.isEmpty() && villageId != null) {
            var poiManager = serverLevel.getPoiManager();
            var nearestBell = poiManager.getInRange(
                    holder -> holder.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING),
                    pos, 64,
                    net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY
            ).findFirst();
            if (nearestBell.isPresent()) {
                String areaName = dev.jwalkin.create_commerce.compat.VillageNameProvider
                        .getDefaultName(serverLevel, nearestBell.get().getPos());
                if (!areaName.isBlank()) {
                    resolvedName = areaName;
                }
            }
        }
        final String finalCustomName = resolvedName;
        final String finalProfileDisplay = profileDisplay;
        serverPlayer.openMenu(lectern, buf -> {
            buf.writeBlockPos(pos);
            buf.writeUtf(finalCustomName, 64);
            buf.writeUtf(finalProfileDisplay, 64);
            buf.writeVarInt(finalNames.size());
            for (String n : finalNames) buf.writeUtf(n, 64);
            buf.writeVarInt(finalAccepts.size());
            for (int i = 0; i < finalAccepts.size(); i++) {
                buf.writeUtf(finalAccepts.get(i), 128);
                buf.writeVarInt(finalInputs.get(i));
                buf.writeVarInt(finalPayouts.get(i));
            }
            buf.writeVarInt(finalBonus.size());
            for (String s : finalBonus) buf.writeUtf(s, 128);
        });
        return InteractionResult.CONSUME;
    }

    @Override
    public RenderShape getRenderShape(@Nonnull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(@Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState state, @Nullable LivingEntity placer, @Nonnull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof DepotLecternBlockEntity lectern)) return;

        // Try to find a nearby village bell within 64 blocks
        var poiManager = serverLevel.getPoiManager();
        var nearbyMeetings = poiManager.getInRange(
                holder -> holder.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING),
                pos, 64,
                net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY
        ).toList();

        if (nearbyMeetings.isEmpty()) {
            lectern.setInoperable(true);
            if (placer instanceof ServerPlayer player) {
                player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                                "[Depot Lectern] No village found nearby."));
            }
            return;
        }

        // Use the closest bell
        var closestPoi = nearbyMeetings.stream()
                .min(java.util.Comparator.comparingDouble(p -> p.getPos().distSqr(pos)))
                .get();
        BlockPos bellPos = closestPoi.getPos();

        // Find existing Depot Lectern in this village to reuse its UUID
        UUID villageId = findExistingVillageUUID(serverLevel, bellPos, 32);
        if (villageId == null) {
            // Generate deterministic UUID from bell position
            long most = ((long) bellPos.getX() << 32) | (bellPos.getZ() & 0xFFFFFFFFL);
            villageId = new UUID(most, bellPos.getY());
        }

        lectern.setVillageUUID(villageId);

        // Register village in data if first time.
        VillageCommerceData data = VillageCommerceData.getOrCreate(serverLevel);
        if (!data.hasVillage(villageId)) {
            data.getOrCreateVillage(villageId);
            String profile = dev.jwalkin.create_commerce.worldgen.DepotLecternWorldGen
                    .assignProfile(serverLevel, bellPos);
            data.setProfile(villageId, profile);
            String defaultName = dev.jwalkin.create_commerce.compat.VillageNameProvider
                    .getDefaultName(serverLevel, bellPos);
            if (!defaultName.isBlank()) {
                data.setCustomName(villageId, defaultName);
            }
        }
    }

    private UUID findExistingVillageUUID(ServerLevel level, BlockPos centre, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(
                centre.offset(-radius, -8, -radius),
                centre.offset(radius, 8, radius))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DepotLecternBlockEntity lectern && lectern.getVillageUUID() != null) {
                return lectern.getVillageUUID();
            }
        }
        return null;
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(@Nonnull Level level, @Nonnull BlockState state, @Nonnull BlockEntityType<T> type) {
        if (!level.isClientSide) {
            return DepotLecternBlockEntity.getTicker();
        }
        return null;
    }

    /**
     * Expands tag entries (#...) into concrete item IDs. Order preserved, duplicates removed.
     */
    private static java.util.List<String> resolveTagsToItems(ServerLevel level, java.util.List<String> entries) {
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
        return new java.util.ArrayList<>(result);
    }
}
