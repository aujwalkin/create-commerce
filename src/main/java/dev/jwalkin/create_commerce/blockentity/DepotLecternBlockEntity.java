package dev.jwalkin.create_commerce.blockentity;

import dev.jwalkin.create_commerce.block.DepotLecternBlock;
import dev.jwalkin.create_commerce.config.CCConfig;
import dev.jwalkin.create_commerce.config.CoinConverter;
import dev.jwalkin.create_commerce.config.ItemConfigLoader;
import dev.jwalkin.create_commerce.config.VillageConfigLoader;
import dev.jwalkin.create_commerce.data.VillageCommerceData;
import dev.jwalkin.create_commerce.menu.DepotLecternMenu;
import dev.jwalkin.create_commerce.registry.CCBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

import java.util.List;
import java.util.UUID;

public class DepotLecternBlockEntity extends BlockEntity implements MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(DepotLecternBlockEntity.class);

    private final ItemStackHandler inputHandler = new ItemStackHandler(9) {
        @Override
        public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
            if (stack.isEmpty()) return false;
            return DepotLecternBlockEntity.this.canAccept(stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            needsProcessing = true;
            setChanged();
        }
    };

    // coins eject from the FACING face when no sink is below
    private final IItemHandler outputCapability = new IItemHandler() {
        @Override public int getSlots() { return 0; }
        @Override public ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }
        @Override public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 0; }
        @Override public boolean isItemValid(int slot, @Nonnull ItemStack stack) { return false; }
    };

    private final ItemStackHandler payoutBuffer = new ItemStackHandler(6) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private boolean needsProcessing = false;
    private UUID villageUUID = null;
    private boolean inoperable = false;
    private int ejectCooldown = 0;

    public DepotLecternBlockEntity(BlockPos pos, BlockState state) {
        super(CCBlockEntities.DEPOT_LECTERN.get(), pos, state);
    }

    public static <T extends BlockEntity> BlockEntityTicker<T> getTicker() {
        return (level, pos, state, be) -> {
            if (!level.isClientSide && be instanceof DepotLecternBlockEntity lectern) {
                lectern.tick();
            }
        };
    }

    private void tick() {
        if (needsProcessing) {
            needsProcessing = false;
            processDeposit();
        }
        if (!(level instanceof ServerLevel sl)) return;
        long time = sl.getGameTime();
        // buffered payout
        if (hasBufferedPayout()) {
            IItemHandler sink = getDownwardSink(sl);
            if (sink != null) {
                drainPayoutBuffer(sink);
            } else {
                // one per 4 ticks prevents belt hover
                if (ejectCooldown > 0) {
                    ejectCooldown--;
                } else {
                    drainOneForward(sl);
                    ejectCooldown = 4;
                }
            }
        }
        if (time % 8 == 0) {
            vacuumNearbyItems(sl);
        }
        if (villageUUID != null) {
            if (time % 100 == 0) {
                ensureLecternRegistration(sl);
            }
            if (time % 400 == 0) {
                refreshVillageStats(sl);
            }
        }
    }

    private void vacuumNearbyItems(ServerLevel sl) {
        if (inoperable) return;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                worldPosition.getX(),
                worldPosition.getY() + 0.5,
                worldPosition.getZ(),
                worldPosition.getX() + 1.0,
                worldPosition.getY() + 1.5,
                worldPosition.getZ() + 1.0);
        List<net.minecraft.world.entity.item.ItemEntity> items =
                sl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box,
                        net.minecraft.world.entity.EntitySelector.ENTITY_STILL_ALIVE);
        if (items.isEmpty()) return;
        for (net.minecraft.world.entity.item.ItemEntity entity : items) {
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) continue;
            ItemStack leftover = net.neoforged.neoforge.items.ItemHandlerHelper
                    .insertItem(inputHandler, stack.copy(), false);
            int picked = stack.getCount() - leftover.getCount();
            if (picked <= 0) continue;
            if (leftover.isEmpty()) {
                entity.discard();
            } else {
                entity.setItem(leftover);
            }
        }
    }

    private void ensureLecternRegistration(ServerLevel level) {
        VillageCommerceData data = VillageCommerceData.getOrCreate(level);
        BlockPos stored = data.getLecternPos(villageUUID);
        if (stored == null) {
            data.setLecternPos(villageUUID, worldPosition);
            return;
        }
        if (stored.equals(worldPosition)) return;
        // only override when the chunk is loaded and the stored pos is verifiably stale
        int cx = stored.getX() >> 4;
        int cz = stored.getZ() >> 4;
        if (level.hasChunk(cx, cz)
                && !(level.getBlockEntity(stored) instanceof DepotLecternBlockEntity)) {
            data.setLecternPos(villageUUID, worldPosition);
        }
    }

    private void refreshVillageStats(ServerLevel level) {
        BlockPos bellPos = bellPosFromVillageUUID(villageUUID);
        if (!level.isLoaded(bellPos)) return;
        int range = 48;
        int population = level.getEntitiesOfClass(
                net.minecraft.world.entity.npc.Villager.class,
                new net.minecraft.world.phys.AABB(
                        bellPos.getX() - range, bellPos.getY() - range, bellPos.getZ() - range,
                        bellPos.getX() + range, bellPos.getY() + range, bellPos.getZ() + range)
        ).size();
        int structures = (int) level.getPoiManager().getInRange(
                holder -> holder.is(net.minecraft.tags.PoiTypeTags.VILLAGE),
                bellPos, range,
                net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY
        ).count();
        VillageCommerceData.getOrCreate(level).updateVillageStats(villageUUID, population, structures);
    }

    /** Inverse of DepotLecternWorldGen.generateVillageUUID. */
    private static BlockPos bellPosFromVillageUUID(UUID id) {
        long most = id.getMostSignificantBits();
        int x = (int) (most >> 32);
        int z = (int) (most & 0xFFFFFFFFL);
        int y = (int) id.getLeastSignificantBits();
        return new BlockPos(x, y, z);
    }

    public boolean canAccept(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (inoperable) return false;
        if (ItemConfigLoader.isRefused(stack)) return false;

        VillageConfigLoader.VillageProfile profile = getProfile();
        if (profile == null) {
            return ItemConfigLoader.getValueInEmeralds(stack) > 0;
        }
        if (matchesList(stack, profile.refuses())) return false;
        boolean inAccepts = matchesList(stack, profile.accepts()) || matchesList(stack, profile.bonusAccepts());
        if (!inAccepts) return false;
        // config is sole authority on pricing
        return ItemConfigLoader.getValueInEmeralds(stack) > 0;
    }

    private @Nullable VillageConfigLoader.VillageProfile getProfile() {
        Level lvl = level;
        if (villageUUID == null || !(lvl instanceof ServerLevel sl)) return null;
        VillageCommerceData data = VillageCommerceData.getOrCreate(sl);
        return VillageConfigLoader.getProfile(data.getProfileName(villageUUID));
    }

    private void processDeposit() {
        Level lvl = level;
        if (lvl == null || lvl.isClientSide) return;
        if (inoperable) {
            return; // don't void contents
        }

        int defaultCap = CCConfig.getCapForSize(10);
        ServerLevel serverLevel = lvl instanceof ServerLevel sl ? sl : null;
        VillageCommerceData data = serverLevel != null ? VillageCommerceData.getOrCreate(serverLevel) : null;
        net.minecraft.core.Registry<Item> itemRegistry = net.minecraft.core.registries.BuiltInRegistries.ITEM;

        VillageConfigLoader.VillageProfile profile = null;
        if (villageUUID != null && data != null) {
            profile = VillageConfigLoader.getProfile(data.getProfileName(villageUUID));
        }

        for (int i = 0; i < inputHandler.getSlots(); i++) {
            ItemStack stack = inputHandler.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            if (ItemConfigLoader.isRefused(stack)) continue;

            // profile gate
            int value;
            if (profile != null) {
                if (matchesList(stack, profile.refuses())) continue;
                boolean accepted = matchesList(stack, profile.accepts()) || matchesList(stack, profile.bonusAccepts());
                if (!accepted) continue;
                value = ItemConfigLoader.getValueInEmeralds(stack);
            } else {
                value = ItemConfigLoader.getValueInEmeralds(stack);
            }
            if (value <= 0) continue;

            // partial batches stay in slot
            int inputAmount = Math.max(1, ItemConfigLoader.getInputAmount(stack));
            int count = stack.getCount();
            int batches = count / inputAmount;
            if (batches <= 0) continue;
            int wantedItems = batches * inputAmount;

            int actualItems = wantedItems;
            int actualBatches = batches;
            if (villageUUID != null && data != null) {
                String itemId = itemRegistry.getKey(stack.getItem()).toString();
                int configuredCap = ItemConfigLoader.getDailyCap(stack);
                int perItemCap = configuredCap < 0 ? defaultCap : configuredCap;
                if (perItemCap <= 0) continue;
                actualItems = data.consumeCap(villageUUID, itemId, wantedItems, perItemCap);
                if (actualItems <= 0) continue;
                actualBatches = actualItems / inputAmount;
                if (actualBatches <= 0) continue;
                actualItems = actualBatches * inputAmount;
            }

            int remaining = count - actualItems;
            if (remaining <= 0) {
                inputHandler.setStackInSlot(i, ItemStack.EMPTY);
            } else {
                ItemStack leftover = stack.copy();
                leftover.setCount(remaining);
                inputHandler.setStackInSlot(i, leftover);
            }
            payOut(value * actualBatches);
        }
        setChanged();
    }

    private static boolean matchesList(ItemStack stack, List<String> list) {
        if (list.isEmpty()) return false;
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        for (String entry : list) {
            if (entry.startsWith("#")) {
                TagKey<Item> tag = TagKey.create(Registries.ITEM,
                        ResourceLocation.parse(entry.substring(1)));
                if (stack.is(tag)) return true;
            } else if (entry.equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    private void payOut(int totalSpurs) {
        Level lvl = level;
        if (lvl == null || totalSpurs <= 0) return;

        LOGGER.info("[Create: Commerce] payOut({}) called, numismaticsLoaded={}",
                totalSpurs, CoinConverter.isNumismaticsLoaded());

        List<ItemStack> coinStacks;
        if (CoinConverter.isNumismaticsLoaded()) {
            coinStacks = CoinConverter.convertToCoins(totalSpurs);
        } else {
            ItemStack currency = CCConfig.getCurrencyItem();
            if (currency.isEmpty()) return;
            coinStacks = new java.util.ArrayList<>();
            int remaining = totalSpurs;
            while (remaining > 0) {
                int dropCount = Math.min(remaining, currency.getMaxStackSize());
                coinStacks.add(currency.copyWithCount(dropCount));
                remaining -= dropCount;
            }
        }
        if (coinStacks.isEmpty()) return;

        IItemHandler downHandler = getDownwardSink(lvl);
        LOGGER.info("[Create: Commerce] downHandler={} coinStacks.size={}", downHandler, coinStacks.size());

        if (downHandler != null) {
            drainPayoutBuffer(downHandler);
            for (ItemStack drop : coinStacks) {
                LOGGER.info("[Create: Commerce] Routing down: {} x{}", drop, drop.getCount());
                ItemStack leftover = net.neoforged.neoforge.items.ItemHandlerHelper
                        .insertItem(downHandler, drop, false);
                if (leftover.isEmpty()) continue;
                // sink full; retry from buffer next tick
                ItemStack overflow = net.neoforged.neoforge.items.ItemHandlerHelper
                        .insertItem(payoutBuffer, leftover, false);
                if (!overflow.isEmpty()) {
                    // buffer full; eject so coins aren't voided
                    LOGGER.warn("[Create: Commerce] payoutBuffer overflow, ejecting {} x{} forward",
                            overflow, overflow.getCount());
                    ejectForward(lvl, overflow);
                }
            }
            return;
        }

        for (ItemStack drop : coinStacks) {
            LOGGER.info("[Create: Commerce] Buffering forward eject: {} x{}", drop, drop.getCount());
            ItemStack overflow = net.neoforged.neoforge.items.ItemHandlerHelper
                    .insertItem(payoutBuffer, drop, false);
            if (!overflow.isEmpty()) {
                LOGGER.warn("[Create: Commerce] payoutBuffer full, ejecting {} x{} immediately", overflow, overflow.getCount());
                ejectForward(lvl, overflow);
            }
        }
    }

    private void drainOneForward(Level lvl) {
        for (int i = 0; i < payoutBuffer.getSlots(); i++) {
            ItemStack stack = payoutBuffer.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            ejectForward(lvl, stack);
            payoutBuffer.setStackInSlot(i, ItemStack.EMPTY);
            return;
        }
    }

    private boolean hasBufferedPayout() {
        for (int i = 0; i < payoutBuffer.getSlots(); i++) {
            if (!payoutBuffer.getStackInSlot(i).isEmpty()) return true;
        }
        return false;
    }

    private void drainPayoutBuffer(IItemHandler sink) {
        for (int i = 0; i < payoutBuffer.getSlots(); i++) {
            ItemStack stack = payoutBuffer.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            ItemStack leftover = net.neoforged.neoforge.items.ItemHandlerHelper
                    .insertItem(sink, stack.copy(), false);
            if (leftover.getCount() == stack.getCount()) return;
            payoutBuffer.setStackInSlot(i, leftover);
        }
    }

    private void ejectForward(Level lvl, ItemStack drop) {
        Direction facing = getBlockState().getValue(DepotLecternBlock.FACING);
        BlockPos dropPos = worldPosition.relative(facing);
        net.minecraft.world.entity.item.ItemEntity entity =
                new net.minecraft.world.entity.item.ItemEntity(
                        lvl,
                        dropPos.getX() + 0.5,
                        dropPos.getY() + 0.5,
                        dropPos.getZ() + 0.5,
                        drop);
        entity.setDeltaMovement(
                facing.getStepX() * 0.1,
                0.2,
                facing.getStepZ() * 0.1);
        lvl.addFreshEntity(entity);
    }

    private @Nullable IItemHandler getDownwardSink(Level lvl) {
        BlockPos below = worldPosition.below();
        BlockState belowState = lvl.getBlockState(below);
        if (!isHopperOrChute(belowState)) return null;
        return lvl.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                below, Direction.UP);
    }

    private static boolean isHopperOrChute(BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.HOPPER)) return true;
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "create".equals(key.getNamespace())
                && ("chute".equals(key.getPath()) || "smart_chute".equals(key.getPath()));
    }

    public IItemHandler getInputHandler() { return inputHandler; }
    public IItemHandler getOutputCapability() { return outputCapability; }
    public UUID getVillageUUID() { return villageUUID; }
    public void setVillageUUID(UUID id) { this.villageUUID = id; setChanged(); }
    public boolean isInoperable() { return inoperable; }
    public void setInoperable(boolean value) { this.inoperable = value; setChanged(); }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.create_commerce.depot_lectern");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, @Nonnull Inventory playerInventory, @Nonnull Player player) {
        return new DepotLecternMenu(containerId, playerInventory, this);
    }

    @Override
    protected void saveAdditional(@Nonnull CompoundTag tag, @Nonnull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("InputItems", inputHandler.serializeNBT(registries));
        tag.put("PayoutBuffer", payoutBuffer.serializeNBT(registries));
        if (villageUUID != null) {
            tag.putUUID("VillageUUID", villageUUID);
        }
        tag.putBoolean("Inoperable", inoperable);
    }

    @Override
    protected void loadAdditional(@Nonnull CompoundTag tag, @Nonnull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("InputItems")) inputHandler.deserializeNBT(registries, tag.getCompound("InputItems"));
        if (tag.contains("PayoutBuffer")) payoutBuffer.deserializeNBT(registries, tag.getCompound("PayoutBuffer"));
        if (tag.hasUUID("VillageUUID")) villageUUID = tag.getUUID("VillageUUID");
        inoperable = tag.getBoolean("Inoperable");
        // deserializeNBT skips onContentsChanged, so re-flag manually
        for (int i = 0; i < inputHandler.getSlots(); i++) {
            if (!inputHandler.getStackInSlot(i).isEmpty()) {
                needsProcessing = true;
                break;
            }
        }
    }
}
