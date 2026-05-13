package dev.jwalkin.create_commerce.network;

import dev.jwalkin.create_commerce.CreateCommerce;
import dev.jwalkin.create_commerce.block.TradeTerminalBlock;
import dev.jwalkin.create_commerce.blockentity.TradeTerminalBlockEntity;
import dev.jwalkin.create_commerce.data.VillageCommerceData;
import dev.jwalkin.create_commerce.data.VillageSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-bound: client requests a refresh of village stats for a Trade Terminal.
 * villageId=null refreshes all linked villages; non-null refreshes one.
 * Force-loads bell chunks so live villager/structure counts are returned.
 */
public record RefreshTerminalPacket(BlockPos terminalPos, UUID villageId) implements CustomPacketPayload {

    public static final Type<RefreshTerminalPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCommerce.MOD_ID, "refresh_terminal"));

    public static final StreamCodec<FriendlyByteBuf, RefreshTerminalPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBlockPos(pkt.terminalPos);
                        buf.writeBoolean(pkt.villageId != null);
                        if (pkt.villageId != null) buf.writeUUID(pkt.villageId);
                    },
                    buf -> {
                        BlockPos pos = buf.readBlockPos();
                        UUID id = buf.readBoolean() ? buf.readUUID() : null;
                        return new RefreshTerminalPacket(pos, id);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RefreshTerminalPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            ServerLevel level = sp.serverLevel();
            if (sp.distanceToSqr(pkt.terminalPos.getX() + 0.5, pkt.terminalPos.getY() + 0.5,
                    pkt.terminalPos.getZ() + 0.5) > 64.0) return;

            BlockEntity be = level.getBlockEntity(pkt.terminalPos);
            if (!(be instanceof TradeTerminalBlockEntity terminal)) return;

            String name = terminal.getTerminalName();
            if (name == null || name.isBlank()) {
                PacketDistributor.sendToPlayer(sp, new UpdateTerminalSnapshotsPacket(List.of(), 0L));
                return;
            }

            VillageCommerceData data = VillageCommerceData.getOrCreate(level);
            long now = level.getGameTime();

            // Global 60-second cooldown per terminal name, prevents spam across
            // same-named terminals or close/reopen exploits.
            long lastRefresh = data.getTerminalRefreshTime(name);
            long cooldownRemaining = TradeTerminalBlockEntity.REFRESH_COOLDOWN_TICKS - (now - lastRefresh);
            if (cooldownRemaining > 0) {
                // Still on cooldown: send current snapshots with remaining time so the client UI stays accurate.
                List<VillageSnapshot> snapshots = TradeTerminalBlock.buildSnapshotsByName(name, level, pkt.terminalPos);
                PacketDistributor.sendToPlayer(sp, new UpdateTerminalSnapshotsPacket(snapshots, cooldownRemaining));
                return;
            }

            // Mark this terminal name as refreshed NOW.
            data.setTerminalRefreshTime(name, now);

            Set<UUID> targets = pkt.villageId != null
                    ? Set.of(pkt.villageId)
                    : data.getVillagesForName(name);

            for (UUID id : targets) {
                // Skip villages already refreshed within the cooldown window
                // (e.g. shared between two differently-named terminals).
                VillageCommerceData.VillageRecord record = data.getOrCreateVillage(id);
                if (now - record.lastRefreshGameTime < TradeTerminalBlockEntity.REFRESH_COOLDOWN_TICKS) continue;

                record.lastRefreshGameTime = now;

                // Anchor on the village's registered depot lectern chunk.
                // Falls back to bell position if no lectern registered.
                BlockPos anchor = data.getLecternPos(id);
                if (anchor == null) anchor = bellPosFromUUID(id);

                int cx = anchor.getX() >> 4;
                int cz = anchor.getZ() >> 4;
                level.getChunk(cx, cz);

                // 24-block AABB for villager/structure queries.
                int range = 24;
                int villagers = level.getEntitiesOfClass(
                        net.minecraft.world.entity.npc.Villager.class,
                        new net.minecraft.world.phys.AABB(
                                anchor.getX() - range, anchor.getY() - range, anchor.getZ() - range,
                                anchor.getX() + range, anchor.getY() + range, anchor.getZ() + range)
                ).size();
                int structures = (int) level.getPoiManager().getInRange(
                        holder -> holder.is(net.minecraft.tags.PoiTypeTags.VILLAGE),
                        anchor, range,
                        net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY
                ).count();
                data.updateVillageStats(id, villagers, structures);
            }

            List<VillageSnapshot> snapshots = TradeTerminalBlock.buildSnapshotsByName(name, level, pkt.terminalPos);
            PacketDistributor.sendToPlayer(sp, new UpdateTerminalSnapshotsPacket(snapshots, 0L));
        });
    }

    private static BlockPos bellPosFromUUID(UUID id) {
        long most = id.getMostSignificantBits();
        int x = (int) (most >> 32);
        int z = (int) (most & 0xFFFFFFFFL);
        int y = (int) id.getLeastSignificantBits();
        return new BlockPos(x, y, z);
    }
}
