package dev.jwalkin.create_commerce.ponder;

import dev.jwalkin.create_commerce.CreateCommerce;
import dev.jwalkin.create_commerce.registry.CCBlocks;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public class CCPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return CreateCommerce.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.addStoryBoard(
                CCBlocks.DEPOT_LECTERN.getId(),
                "depot_lectern",
                DepotLecternScenes::automation);
    }
}
