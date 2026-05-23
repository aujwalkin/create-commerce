package dev.jwalkin.create_commerce.network;

import dev.jwalkin.create_commerce.CreateCommerce;
import dev.jwalkin.create_commerce.data.VillageSnapshot;
import dev.jwalkin.create_commerce.menu.TradeTerminalMenu;
import dev.jwalkin.create_commerce.screen.TradeTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-bound: replaces the open Trade Terminal's snapshots so the menu
 * redraws without reopening (preserves scroll/detail state).
 * Also carries cooldown remaining ticks so the client UI stays accurate.
 */
public record UpdateTerminalSnapshotsPacket(List<VillageSnapshot> snapshots, long cooldownRemainingTicks) implements CustomPacketPayload {

    public static final Type<UpdateTerminalSnapshotsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCommerce.MOD_ID, "update_terminal_snapshots"));

    public static final StreamCodec<FriendlyByteBuf, UpdateTerminalSnapshotsPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.snapshots.size());
                        for (VillageSnapshot snap : pkt.snapshots) {
                            VillageSnapshot.STREAM_CODEC.encode(buf, snap);
                        }
                        buf.writeVarLong(pkt.cooldownRemainingTicks);
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        List<VillageSnapshot> out = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) out.add(VillageSnapshot.STREAM_CODEC.decode(buf));
                        long cooldownRemaining = buf.readVarLong();
                        return new UpdateTerminalSnapshotsPacket(out, cooldownRemaining);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UpdateTerminalSnapshotsPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.player.containerMenu instanceof TradeTerminalMenu menu) {
                menu.replaceSnapshots(pkt.snapshots);
                menu.setCooldownRemainingTicks(pkt.cooldownRemainingTicks);
                // Sync cooldown to the screen so it shows the correct timer
                if (mc.screen instanceof TradeTerminalScreen screen) {
                    screen.syncCooldownFromServer(pkt.cooldownRemainingTicks);
                }
            }
        });
    }
}
