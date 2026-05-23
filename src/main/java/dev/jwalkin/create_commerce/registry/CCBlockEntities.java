package dev.jwalkin.create_commerce.registry;

import dev.jwalkin.create_commerce.CreateCommerce;
import dev.jwalkin.create_commerce.block.DepotLecternBlock;
import dev.jwalkin.create_commerce.blockentity.DepotLecternBlockEntity;
import dev.jwalkin.create_commerce.blockentity.TradeTerminalBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CCBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateCommerce.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DepotLecternBlockEntity>> DEPOT_LECTERN =
            BLOCK_ENTITIES.register("depot_lectern", () ->
                    BlockEntityType.Builder.of(DepotLecternBlockEntity::new,
                            CCBlocks.DEPOT_LECTERN.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TradeTerminalBlockEntity>> TRADE_TERMINAL =
            BLOCK_ENTITIES.register("trade_terminal", () ->
                    BlockEntityType.Builder.of(TradeTerminalBlockEntity::new,
                            CCBlocks.TRADE_TERMINAL.get()).build(null));

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                DEPOT_LECTERN.get(),
                (be, side) -> {
                    // DOWN and FACING expose the empty output cap so hoppers below can receive
                    // coins and automation on the front face picks up dropped entities instead.
                    if (side == Direction.DOWN) return be.getOutputCapability();
                    Direction facing = be.getBlockState().getValue(DepotLecternBlock.FACING);
                    if (side == facing) return be.getOutputCapability();
                    return be.getInputHandler();
                }
        );
    }
}
