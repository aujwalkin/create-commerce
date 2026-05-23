package dev.jwalkin.create_commerce.registry;

import dev.jwalkin.create_commerce.CreateCommerce;
import dev.jwalkin.create_commerce.menu.DepotLecternMenu;
import dev.jwalkin.create_commerce.menu.TradeTerminalMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CCMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, CreateCommerce.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<DepotLecternMenu>> DEPOT_LECTERN_MENU =
            MENU_TYPES.register("depot_lectern", () ->
                    IMenuTypeExtension.create(DepotLecternMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<TradeTerminalMenu>> TRADE_TERMINAL_MENU =
            MENU_TYPES.register("trade_terminal", () ->
                    IMenuTypeExtension.create(TradeTerminalMenu::new));
}
