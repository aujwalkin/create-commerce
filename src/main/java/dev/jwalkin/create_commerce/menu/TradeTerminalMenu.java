package dev.jwalkin.create_commerce.menu;

import dev.jwalkin.create_commerce.data.VillageSnapshot;
import dev.jwalkin.create_commerce.registry.CCMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class TradeTerminalMenu extends AbstractContainerMenu {

    private final BlockPos blockPos;
    private final List<VillageSnapshot> snapshots;
    private String terminalName;
    /** Cooldown remaining ticks, synced from server on open and on refresh. */
    private long cooldownRemainingTicks;

    // Server-side constructor (called by block entity createMenu)
    public TradeTerminalMenu(int containerId, Inventory playerInv, BlockPos pos) {
        super(CCMenuTypes.TRADE_TERMINAL_MENU.get(), containerId);
        this.blockPos = pos;
        this.snapshots = new ArrayList<>();
        this.terminalName = "";
        this.cooldownRemainingTicks = 0L;
    }

    // Client-side constructor (called by IMenuTypeExtension factory)
    public TradeTerminalMenu(int containerId, Inventory playerInv, FriendlyByteBuf data) {
        super(CCMenuTypes.TRADE_TERMINAL_MENU.get(), containerId);
        this.blockPos = data.readBlockPos();
        this.terminalName = data.readUtf(64);
        int count = data.readVarInt();
        this.snapshots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            this.snapshots.add(VillageSnapshot.STREAM_CODEC.decode(data));
        }
        this.cooldownRemainingTicks = data.readVarLong();
    }

    public BlockPos getBlockPos() { return blockPos; }
    public List<VillageSnapshot> getSnapshots() { return snapshots; }
    public String getTerminalName() { return terminalName; }
    public void setTerminalNameLocal(String name) { this.terminalName = name == null ? "" : name; }
    public long getCooldownRemainingTicks() { return cooldownRemainingTicks; }

    /** Replaces snapshots in-place for refresh without reopening. */
    public void replaceSnapshots(List<VillageSnapshot> updated) {
        this.snapshots.clear();
        if (updated != null) this.snapshots.addAll(updated);
    }

    /** Updates cooldown remaining from server (called after refresh response). */
    public void setCooldownRemainingTicks(long ticks) {
        this.cooldownRemainingTicks = ticks;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(
                blockPos.getX() + 0.5,
                blockPos.getY() + 0.5,
                blockPos.getZ() + 0.5) < 64.0;
    }
}
