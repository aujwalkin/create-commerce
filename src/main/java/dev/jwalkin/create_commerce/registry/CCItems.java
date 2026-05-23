package dev.jwalkin.create_commerce.registry;

import dev.jwalkin.create_commerce.CreateCommerce;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CCItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CreateCommerce.MOD_ID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateCommerce.MOD_ID);

    public static final DeferredItem<?> DEPOT_LECTERN_ITEM =
            ITEMS.registerSimpleBlockItem("depot_lectern", CCBlocks.DEPOT_LECTERN);

    public static final DeferredItem<?> TRADE_TERMINAL_ITEM =
            ITEMS.registerSimpleBlockItem("trade_terminal", CCBlocks.TRADE_TERMINAL);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATE_COMMERCE_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.create_commerce"))
                    .icon(() -> new ItemStack(DEPOT_LECTERN_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(DEPOT_LECTERN_ITEM.get());
                        output.accept(TRADE_TERMINAL_ITEM.get());
                    })
                    .build());
}
