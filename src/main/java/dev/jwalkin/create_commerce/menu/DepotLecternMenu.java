package dev.jwalkin.create_commerce.menu;

import dev.jwalkin.create_commerce.blockentity.DepotLecternBlockEntity;
import dev.jwalkin.create_commerce.registry.CCMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

public class DepotLecternMenu extends AbstractContainerMenu {

    private final BlockPos blockPos;
    private static final int INPUT_SLOTS = 9;
    private static final int PLAYER_SLOTS = 36;

    private final List<String> terminalNames;
    private final List<String> acceptedItems;
    private final List<Integer> acceptedInputAmounts;
    private final List<Integer> acceptedPayouts;
    private final List<String> bonusItems;
    private String customName;
    private final String profileDisplay;

    // Server-side constructor (called by block entity)
    public DepotLecternMenu(int containerId, Inventory playerInv, DepotLecternBlockEntity be) {
        super(CCMenuTypes.DEPOT_LECTERN_MENU.get(), containerId);
        this.blockPos = be.getBlockPos();
        this.terminalNames = new ArrayList<>();
        this.acceptedItems = new ArrayList<>();
        this.acceptedInputAmounts = new ArrayList<>();
        this.acceptedPayouts = new ArrayList<>();
        this.bonusItems = new ArrayList<>();
        this.customName = "";
        this.profileDisplay = "";
        addDepositSlots(be.getInputHandler());
        addPlayerInventory(playerInv);
    }

    // Client-side constructor (called by IMenuTypeExtension factory via FriendlyByteBuf)
    public DepotLecternMenu(int containerId, Inventory playerInv, FriendlyByteBuf data) {
        super(CCMenuTypes.DEPOT_LECTERN_MENU.get(), containerId);
        BlockPos pos = data.readBlockPos();
        this.blockPos = pos;

        this.customName = data.readUtf(64);
        this.profileDisplay = data.readUtf(64);

        int nameCount = data.readVarInt();
        this.terminalNames = new ArrayList<>(nameCount);
        for (int i = 0; i < nameCount; i++) terminalNames.add(data.readUtf(64));

        int acceptCount = data.readVarInt();
        this.acceptedItems = new ArrayList<>(acceptCount);
        this.acceptedInputAmounts = new ArrayList<>(acceptCount);
        this.acceptedPayouts = new ArrayList<>(acceptCount);
        for (int i = 0; i < acceptCount; i++) {
            acceptedItems.add(data.readUtf(128));
            acceptedInputAmounts.add(data.readVarInt());
            acceptedPayouts.add(data.readVarInt());
        }

        int bonusCount = data.readVarInt();
        this.bonusItems = new ArrayList<>(bonusCount);
        for (int i = 0; i < bonusCount; i++) bonusItems.add(data.readUtf(128));

        IItemHandler handler;
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        if (be instanceof DepotLecternBlockEntity lectern) {
            handler = lectern.getInputHandler();
        } else {
            handler = new ItemStackHandler(INPUT_SLOTS);
        }
        addDepositSlots(handler);
        addPlayerInventory(playerInv);
    }

    public BlockPos getBlockPos() { return blockPos; }
    public List<String> getTerminalNames() { return terminalNames; }
    public List<String> getAcceptedItems() { return acceptedItems; }
    public List<Integer> getAcceptedInputAmounts() { return acceptedInputAmounts; }
    public List<Integer> getAcceptedPayouts() { return acceptedPayouts; }
    public List<String> getBonusItems() { return bonusItems; }
    public String getCustomName() { return customName; }
    public void setCustomNameLocal(String name) { this.customName = name == null ? "" : name; }
    public String getProfileDisplay() { return profileDisplay; }

    private void addDepositSlots(IItemHandler handler) {
        // Use GatedSlot so the screen can hide slots on non-Deposit tabs.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new GatedSlot(handler, row * 3 + col, 93 + col * 18, 56 + row * 18));
            }
        }
    }

    private void addPlayerInventory(Inventory playerInv) {
        // Slot Y aligned with DepotLecternScreen's INV_Y/HOTBAR_Y.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 39 + col * 18, 162 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 39 + col * 18, 220));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < INPUT_SLOTS) {
                if (!moveItemStackTo(stack, INPUT_SLOTS, INPUT_SLOTS + PLAYER_SLOTS, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!moveItemStackTo(stack, 0, INPUT_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(
                blockPos.getX() + 0.5,
                blockPos.getY() + 0.5,
                blockPos.getZ() + 0.5) < 64.0;
    }
}
