package dev.jwalkin.create_commerce.network;

import dev.jwalkin.create_commerce.CreateCommerce;
import dev.jwalkin.create_commerce.blockentity.TradeTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetTerminalNamePacket(BlockPos terminalPos, String name) implements CustomPacketPayload {

    public static final Type<SetTerminalNamePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCommerce.MOD_ID, "set_terminal_name"));

    public static final StreamCodec<FriendlyByteBuf, SetTerminalNamePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> { buf.writeBlockPos(pkt.terminalPos); buf.writeUtf(pkt.name, 64); },
                    buf -> new SetTerminalNamePacket(buf.readBlockPos(), buf.readUtf(64))
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetTerminalNamePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            ServerLevel level = sp.serverLevel();
            String name = pkt.name == null ? "" : pkt.name.trim();
            if (name.length() > 32) return;
            if (sp.distanceToSqr(pkt.terminalPos.getX() + 0.5, pkt.terminalPos.getY() + 0.5, pkt.terminalPos.getZ() + 0.5) > 64.0) return;

            BlockEntity be = level.getBlockEntity(pkt.terminalPos);
            if (!(be instanceof TradeTerminalBlockEntity terminal)) return;
            terminal.setTerminalName(name);
        });
    }
}
