package dev.jwalkin.create_commerce.ponder;

import dev.jwalkin.create_commerce.config.CoinConverter;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Ponder scene for the Depot Lectern. */
public class DepotLecternScenes {

    public static void automation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("depot_lectern", "Automating the Depot Lectern");
        // Plate sized to match the schematic's 5-wide floor.
        scene.configureBasePlate(0, 6, 5);
        scene.scaleSceneView(0.55f);
        scene.setSceneOffsetY(-1.5f);
        scene.showBasePlate();
        scene.idle(5);

        // Phase 1: reveal only the lectern.
        scene.world().showSection(util.select().position(2, 4, 10), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(130)
                .attachKeyFrame()
                .pointAt(util.vector().topOf(util.grid().at(2, 4, 10)))
                .placeNearTarget()
                .text("The Depot Lectern accepts items defined by the village's profile and pays out Spur in return.");
        scene.idle(130);

        // Phase 2: hopper-from-behind input + barrel above it.
        scene.world().showSection(util.select().fromTo(2, 4, 11, 2, 5, 11), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(130)
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(util.grid().at(2, 4, 11)))
                .placeNearTarget()
                .text("Hoppers and funnels can push items into the lectern from any side except the front or underneath.");
        scene.idle(130);

        // Phase 3: floating chute + barrel directly overhead.
        scene.world().showSection(util.select().fromTo(2, 7, 10, 2, 8, 10), Direction.DOWN);
        scene.idle(15);

        ItemStack wheat = new ItemStack(Items.WHEAT);
        scene.world().createItemEntity(
                util.vector().centerOf(util.grid().at(2, 6, 10)),
                util.vector().of(0, -0.05, 0),
                wheat);
        scene.idle(25);
        scene.world().modifyEntities(ItemEntity.class, Entity::discard);

        scene.overlay().showText(80)
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(util.grid().at(2, 6, 10)))
                .placeNearTarget()
                .text("Items dropped onto the lectern from above are vacuumed in.");
        scene.idle(80);

        // Phase 4: Spur ejects onto the front belt.
        scene.world().showSection(util.select().fromTo(2, 3, 5, 2, 3, 8), Direction.UP);
        scene.world().showSection(util.select().position(3, 3, 8), Direction.DOWN);
        scene.idle(20);

        ItemStack spur = CoinConverter.getSpurDisplayStack();
        scene.world().createItemEntity(
                util.vector().centerOf(util.grid().at(2, 4, 9)),
                util.vector().of(0, 0.2, -0.15),
                spur);
        scene.idle(35);
        scene.world().modifyEntities(ItemEntity.class, Entity::discard);

        scene.overlay().showText(80)
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(util.grid().at(2, 3, 7)))
                .placeNearTarget()
                .text("When a batch processes, Spur ejects from the front face.");
        scene.idle(80);

        // Phase 5: swap the front belt for the chute-below + bottom-belt config.
        scene.world().hideSection(util.select().fromTo(2, 3, 5, 2, 3, 8), Direction.DOWN);
        scene.world().hideSection(util.select().position(3, 3, 8), Direction.UP);
        scene.idle(20);

        scene.world().showSection(util.select().position(2, 3, 10), Direction.UP);
        scene.world().showSection(util.select().fromTo(2, 1, 5, 2, 1, 10), Direction.UP);
        scene.world().showSection(util.select().position(3, 1, 10), Direction.DOWN);
        scene.idle(20);

        scene.world().createItemEntity(
                util.vector().centerOf(util.grid().at(2, 3, 10)),
                util.vector().of(0, -0.2, 0),
                spur);
        scene.idle(30);
        scene.world().modifyEntities(ItemEntity.class, Entity::discard);

        scene.overlay().showText(160)
                .attachKeyFrame()
                .pointAt(util.vector().centerOf(util.grid().at(2, 1, 8)))
                .placeNearTarget()
                .text("If a Hopper or Chute sits directly below the lectern, Spur routes straight down into it instead of ejecting forward.");
        scene.idle(160);

        scene.markAsFinished();
    }
}
