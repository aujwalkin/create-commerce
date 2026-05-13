package dev.jwalkin.create_commerce.registry;

import dev.jwalkin.create_commerce.CreateCommerce;
import dev.jwalkin.create_commerce.block.DepotLecternBlock;
import dev.jwalkin.create_commerce.block.TradeTerminalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CCBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CreateCommerce.MOD_ID);

    public static final DeferredBlock<DepotLecternBlock> DEPOT_LECTERN =
            BLOCKS.register("depot_lectern", () ->
                    new DepotLecternBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0f)
                            .requiresCorrectToolForDrops()
                            .pushReaction(PushReaction.BLOCK)
                            .noOcclusion()));

    public static final DeferredBlock<TradeTerminalBlock> TRADE_TERMINAL =
            BLOCKS.register("trade_terminal", () ->
                    new TradeTerminalBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.0f)
                            .requiresCorrectToolForDrops()
                            .pushReaction(PushReaction.BLOCK)));
}
