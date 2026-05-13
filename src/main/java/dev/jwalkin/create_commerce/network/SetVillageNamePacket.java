package dev.jwalkin.create_commerce.network;

import dev.jwalkin.create_commerce.CreateCommerce;
import dev.jwalkin.create_commerce.blockentity.DepotLecternBlockEntity;
import dev.jwalkin.create_commerce.data.VillageCommerceData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record SetVillageNamePacket(BlockPos lecternPos, String name) implements CustomPacketPayload {

    public static final Type<SetVillageNamePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCommerce.MOD_ID, "set_village_name"));

    public static final StreamCodec<FriendlyByteBuf, SetVillageNamePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> { buf.writeBlockPos(pkt.lecternPos); buf.writeUtf(pkt.name, 64); },
                    buf -> new SetVillageNamePacket(buf.readBlockPos(), buf.readUtf(64))
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetVillageNamePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            ServerLevel level = sp.serverLevel();
            String name = pkt.name == null ? "" : pkt.name.trim();
            if (name.length() > 32) return;
            if (sp.distanceToSqr(pkt.lecternPos.getX() + 0.5, pkt.lecternPos.getY() + 0.5, pkt.lecternPos.getZ() + 0.5) > 64.0) return;

            BlockEntity be = level.getBlockEntity(pkt.lecternPos);
            if (!(be instanceof DepotLecternBlockEntity lectern)) return;
            UUID villageId = lectern.getVillageUUID();
            if (villageId == null) return;

            VillageCommerceData data = VillageCommerceData.getOrCreate(level);
            data.setCustomName(villageId, name);
            lectern.setChanged();
        });
    }
}
