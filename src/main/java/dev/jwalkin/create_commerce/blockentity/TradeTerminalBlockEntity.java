package dev.jwalkin.create_commerce.blockentity;

import dev.jwalkin.create_commerce.menu.TradeTerminalMenu;
import dev.jwalkin.create_commerce.registry.CCBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import javax.annotation.Nonnull;

public class TradeTerminalBlockEntity extends BlockEntity implements MenuProvider {

    private String terminalName = "";

    /** Cooldown duration shared with the refresh packet handler. */
    public static final long REFRESH_COOLDOWN_TICKS = 1200L; // 60 seconds at 20 tps

    public TradeTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(CCBlockEntities.TRADE_TERMINAL.get(), pos, state);
    }

    public String getTerminalName() {
        return terminalName;
    }

    public void setTerminalName(String name) {
        this.terminalName = name == null ? "" : name;
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.create_commerce.trade_terminal");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, @Nonnull Inventory playerInventory, @Nonnull Player player) {
        return new TradeTerminalMenu(containerId, playerInventory, worldPosition);
    }

    @Override
    protected void saveAdditional(@Nonnull CompoundTag tag, @Nonnull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("TerminalName", terminalName);
    }

    @Override
    protected void loadAdditional(@Nonnull CompoundTag tag, @Nonnull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        terminalName = tag.getString("TerminalName");
    }
}
